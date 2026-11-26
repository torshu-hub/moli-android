use moli_core::{InstanceId, Semantic, Value};
use serde_json::Value as Json;

use super::*;

fn fixture(name: &str) -> Json {
    let path = format!(
        "{}/../../integrations/netatmo/fixtures/{name}",
        env!("CARGO_MANIFEST_DIR")
    );
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn instance() -> InstanceId {
    InstanceId::from("netatmo")
}

fn keys(d: &Device) -> Vec<&str> {
    d.points.iter().map(|p| &*p.key).collect()
}

#[test]
fn ids_keep_letters_and_digits() {
    assert_eq!(alnum("aa:bb:CC:dd:ee:01"), "aabbccddee01");
    assert_eq!(room_native("Home-1", "2"), "room_home1_2");
    assert_eq!(home_native("5E0A"), "home_5e0a");
}

#[test]
fn a_room_is_a_valve_the_dashboard_knows() {
    let homes = model::parse_homesdata(&fixture("energy-homesdata.json"), None);
    let home = &homes[0];
    let d = room_device(&instance(), home, &home.rooms[0]);
    assert_eq!(d.native_name.as_ref(), "Salon");
    assert_eq!(d.model.as_deref(), Some("Smart Radiator Valve"));
    assert_eq!(
        keys(&d),
        [
            "local_temperature",
            "occupied_heating_setpoint",
            "system_mode",
            "boost",
            "heat_demand",
            "window_open"
        ]
    );
    let setpoint = &d.points[1];
    assert!(setpoint.access.write);
    assert_eq!(setpoint.semantic, Semantic::Temperature);
    let thermostat = room_device(&instance(), home, &home.rooms[1]);
    assert_eq!(thermostat.model.as_deref(), Some("Smart Thermostat"));
}

#[test]
fn the_home_offers_its_modes_and_schedules() {
    let homes = model::parse_homesdata(&fixture("energy-homesdata.json"), None);
    let labels = schedule_labels(&homes[0].schedules);
    assert_eq!(
        labels,
        [
            ("sched-week".into(), "Semaine".into()),
            ("sched-holiday".into(), "Vacances".into())
        ]
    );
    let d = home_device(&instance(), &homes[0], &labels);
    assert_eq!(keys(&d), ["therm_mode", "schedule"]);
    let Kind::Enum { values } = &d.points[1].kind else {
        panic!("a choice")
    };
    assert_eq!(values.len(), 2);
    // No schedule, no choice.
    assert_eq!(
        keys(&home_device(&instance(), &homes[0], &[])),
        ["therm_mode"]
    );
}

#[test]
fn two_schedules_with_one_name_stay_distinct() {
    let s = |id: &str| Schedule {
        id: id.into(),
        name: "Hiver".into(),
        selected: false,
    };
    let labels = schedule_labels(&[s("a"), s("b")]);
    assert_eq!(labels[0].1, "Hiver");
    assert_eq!(labels[1].1, "Hiver (b)");
}

#[test]
fn modules_carry_their_battery_signal_and_boiler() {
    let homes = model::parse_homesdata(&fixture("energy-homesdata.json"), None);
    let by_kind = |k: &str| homes[0].modules.iter().find(|m| m.kind == k).unwrap();
    assert_eq!(
        keys(&module_device(&instance(), by_kind("NRV"))),
        ["battery", "signal"]
    );
    assert_eq!(
        keys(&module_device(&instance(), by_kind("NATherm1"))),
        ["battery", "signal", "boiler"]
    );
    assert_eq!(
        keys(&module_device(&instance(), by_kind("NAPlug"))),
        ["wifi_signal", "signal"]
    );
}

#[test]
fn a_station_publishes_the_measures_it_gives() {
    let sensors = model::parse_sensors(&fixture("weather-stationsdata.json"));
    let d = |i: usize| sensor_device(&instance(), &sensors[i]);
    assert_eq!(
        keys(&d(0)),
        [
            "temperature",
            "min_temperature",
            "max_temperature",
            "humidity",
            "co2",
            "noise",
            "pressure",
            "absolute_pressure",
            "temperature_trend",
            "pressure_trend",
            "signal"
        ]
    );
    let wind = d(2);
    assert_eq!(
        keys(&wind),
        [
            "wind_speed",
            "wind_gust",
            "wind_direction",
            "gust_direction",
            "battery",
            "signal"
        ]
    );
    assert_eq!(wind.points[0].semantic, Semantic::WindSpeed);
    assert_eq!(wind.points[0].unit, Some(Unit::KilometrePerHour));
    let rain = d(3);
    assert_eq!(
        keys(&rain),
        ["rain", "rain_rate", "rain_24h", "battery", "signal"]
    );
    assert_eq!(rain.points[1].semantic, Semantic::RainRate);
    assert_eq!(rain.points[1].unit, Some(Unit::MillimetrePerHour));
    let coach = model::parse_sensors(&fixture("homecoach-data.json"));
    let air = sensor_device(&instance(), &coach[0]);
    assert!(keys(&air).contains(&"health"));
    assert_eq!(air.model.as_deref(), Some("Healthy Home Coach"));
}

#[test]
fn every_point_of_a_device_has_one_key() {
    for file in ["weather-stationsdata.json", "homecoach-data.json"] {
        for s in model::parse_sensors(&fixture(file)) {
            let d = sensor_device(&instance(), &s);
            let mut seen = HashSet::new();
            assert!(
                d.points.iter().all(|p| seen.insert(p.key.clone())),
                "{}",
                s.kind
            );
        }
    }
}

#[test]
fn netatmo_modes_become_valve_modes() {
    for (netatmo, valve) in [
        ("home", "auto"),
        ("schedule", "auto"),
        ("hg", "auto"),
        ("away", "auto"),
        ("manual", "heat"),
        ("max", "heat"),
        ("off", "off"),
    ] {
        assert_eq!(system_mode(netatmo), valve, "{netatmo}");
    }
}

#[test]
fn a_setpoint_is_a_manual_mode_at_the_half_degree() {
    let a = plan_room("occupied_heating_setpoint", &Value::Float(20.74), None).unwrap();
    assert_eq!(
        a,
        Action::Room {
            mode: "manual",
            temperature: Some(20.5)
        }
    );
    let (path, form) = request_of(&a, "home-1", Some("2"));
    assert_eq!(path, "/api/setroomthermpoint");
    assert_eq!(form, "home_id=home-1&room_id=2&mode=manual&temp=20.5");
}

#[test]
fn modes_of_a_room() {
    let held = RoomStatus {
        setpoint: Some(19.0),
        ..RoomStatus::default()
    };
    assert_eq!(
        plan_room("system_mode", &Value::Text("auto".into()), None).unwrap(),
        Action::Room {
            mode: "home",
            temperature: None
        }
    );
    assert_eq!(
        plan_room("system_mode", &Value::Text("off".into()), None).unwrap(),
        Action::Room {
            mode: "off",
            temperature: None
        }
    );
    assert_eq!(
        plan_room("system_mode", &Value::Text("heat".into()), Some(&held)).unwrap(),
        Action::Room {
            mode: "manual",
            temperature: Some(19.0)
        }
    );
    assert_eq!(
        plan_room("boost", &Value::Bool(true), None).unwrap(),
        Action::Room {
            mode: "max",
            temperature: None
        }
    );
    assert_eq!(
        plan_room("boost", &Value::Bool(false), None).unwrap(),
        Action::Room {
            mode: "home",
            temperature: None
        }
    );
}

#[test]
fn heat_without_a_known_setpoint_is_refused_not_guessed() {
    assert!(plan_room("system_mode", &Value::Text("heat".into()), None).is_err());
    assert!(plan_room("system_mode", &Value::Text("cool".into()), None).is_err());
    assert!(plan_room("local_temperature", &Value::Float(1.0), None).is_err());
}

#[test]
fn the_home_orders() {
    let schedules = vec![
        ("s1".to_owned(), "Semaine".to_owned()),
        ("s2".to_owned(), "Vacances".to_owned()),
    ];
    let away = plan_home("therm_mode", &Value::Text("away".into()), &schedules).unwrap();
    assert_eq!(
        request_of(&away, "h", None),
        ("/api/setthermmode", "home_id=h&mode=away".to_owned())
    );
    let hg = plan_home("therm_mode", &Value::Text("hg".into()), &schedules).unwrap();
    assert_eq!(hg, Action::Home("hg"));
    let s = plan_home("schedule", &Value::Text("Vacances".into()), &schedules).unwrap();
    assert_eq!(
        request_of(&s, "h", None),
        (
            "/api/switchhomeschedule",
            "home_id=h&schedule_id=s2".to_owned()
        )
    );
    assert!(plan_home("schedule", &Value::Text("Inconnu".into()), &schedules).is_err());
    assert!(plan_home("therm_mode", &Value::Text("boost".into()), &schedules).is_err());
}

#[test]
fn the_config_refuses_unknown_options() {
    assert!(serde_json::from_value::<Config>(serde_json::json!({"home_id": "h"})).is_ok());
    assert!(serde_json::from_value::<Config>(serde_json::json!({})).is_ok());
    assert!(serde_json::from_value::<Config>(serde_json::json!({"nope": 1})).is_err());
}
