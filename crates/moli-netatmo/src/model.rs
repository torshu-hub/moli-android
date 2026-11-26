//! What Netatmo answers, as the driver needs it. Lenient on purpose: a field
//! that is missing is absent, never an error (the shapes follow Netatmo's API
//! as Home Assistant's pyatmo reads it; not checked on every device model).

use serde_json::Value as Json;

fn text(v: &Json, key: &str) -> Option<String> {
    v.get(key)?.as_str().map(str::to_owned)
}

fn number(v: &Json, key: &str) -> Option<f64> {
    v.get(key)?.as_f64()
}

fn int(v: &Json, key: &str) -> Option<i64> {
    v.get(key)?.as_i64()
}

fn flag(v: &Json, key: &str) -> Option<bool> {
    v.get(key)?.as_bool()
}

fn list<'a>(v: &'a Json, key: &str) -> impl Iterator<Item = &'a Json> {
    v.get(key)
        .and_then(Json::as_array)
        .into_iter()
        .flat_map(|a| a.iter())
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Room {
    pub(crate) id: String,
    pub(crate) name: String,
    pub(crate) module_ids: Vec<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Module {
    pub(crate) id: String,
    /// `NAPlug` (relay), `NATherm1` (thermostat), `NRV` (valve), `OTM`…
    pub(crate) kind: String,
    pub(crate) name: String,
    pub(crate) room_id: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Schedule {
    pub(crate) id: String,
    pub(crate) name: String,
    pub(crate) selected: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Home {
    pub(crate) id: String,
    pub(crate) name: String,
    pub(crate) therm_mode: Option<String>,
    pub(crate) rooms: Vec<Room>,
    pub(crate) modules: Vec<Module>,
    pub(crate) schedules: Vec<Schedule>,
}

impl Home {
    /// The heating hardware of the home (thermostats, valves, the relay): a
    /// home with only cameras has no room to publish.
    pub(crate) fn has_heating(&self) -> bool {
        !self.rooms.is_empty()
    }
}

/// `/api/homesdata`: the homes of the account (only `only`, if given).
pub(crate) fn parse_homesdata(answer: &Json, only: Option<&str>) -> Vec<Home> {
    list(&answer["body"], "homes")
        .filter_map(|home| {
            let id = text(home, "id")?;
            if only.is_some_and(|wanted| wanted != id) {
                return None;
            }
            Some(Home {
                name: text(home, "name").unwrap_or_else(|| id.clone()),
                therm_mode: text(home, "therm_mode"),
                rooms: list(home, "rooms")
                    .filter_map(|r| {
                        let id = text(r, "id")?;
                        Some(Room {
                            name: text(r, "name").unwrap_or_else(|| id.clone()),
                            module_ids: list(r, "module_ids")
                                .filter_map(|m| m.as_str().map(str::to_owned))
                                .collect(),
                            id,
                        })
                    })
                    .collect(),
                modules: list(home, "modules")
                    .filter_map(|m| {
                        let id = text(m, "id")?;
                        Some(Module {
                            kind: text(m, "type").unwrap_or_default(),
                            name: text(m, "name").unwrap_or_else(|| id.clone()),
                            room_id: text(m, "room_id"),
                            id,
                        })
                    })
                    .collect(),
                schedules: list(home, "therm_schedules")
                    .filter_map(|s| {
                        let id = text(s, "id")?;
                        // A cooling or event schedule is not a heating one.
                        if text(s, "type").is_some_and(|t| t != "therm") {
                            return None;
                        }
                        Some(Schedule {
                            name: text(s, "name").unwrap_or_else(|| id.clone()),
                            selected: flag(s, "selected").unwrap_or(false),
                            id,
                        })
                    })
                    .collect(),
                id,
            })
        })
        .collect()
}

#[derive(Clone, Debug, Default, PartialEq)]
pub(crate) struct RoomStatus {
    pub(crate) id: String,
    pub(crate) reachable: Option<bool>,
    pub(crate) measured: Option<f64>,
    pub(crate) setpoint: Option<f64>,
    /// `schedule`, `home`, `manual`, `max` (boost), `off`, `hg`, `away`.
    pub(crate) mode: Option<String>,
    pub(crate) heating_power: Option<f64>,
    pub(crate) open_window: Option<bool>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub(crate) struct ModuleStatus {
    pub(crate) id: String,
    pub(crate) reachable: Option<bool>,
    pub(crate) battery_percent: Option<i64>,
    pub(crate) rf_strength: Option<i64>,
    pub(crate) wifi_strength: Option<i64>,
    pub(crate) boiler: Option<bool>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub(crate) struct HomeStatus {
    pub(crate) therm_mode: Option<String>,
    pub(crate) rooms: Vec<RoomStatus>,
    pub(crate) modules: Vec<ModuleStatus>,
}

/// The battery as a percentage: what Home Assistant (pyatmo) shows from the
/// state Netatmo gives, when the module gives no percentage itself.
fn battery_percent(module: &Json) -> Option<i64> {
    if let Some(p) = int(module, "battery_percent") {
        return Some(p);
    }
    Some(match module.get("battery_state")?.as_str()? {
        "max" => 100,
        "full" => 90,
        "high" => 75,
        "medium" => 50,
        "low" => 25,
        "very_low" => 10,
        _ => 0,
    })
}

/// `/api/homestatus`.
pub(crate) fn parse_homestatus(answer: &Json) -> HomeStatus {
    let home = &answer["body"]["home"];
    HomeStatus {
        therm_mode: text(home, "therm_mode"),
        rooms: list(home, "rooms")
            .filter_map(|r| {
                Some(RoomStatus {
                    id: text(r, "id")?,
                    reachable: flag(r, "reachable"),
                    measured: number(r, "therm_measured_temperature"),
                    setpoint: number(r, "therm_setpoint_temperature"),
                    mode: text(r, "therm_setpoint_mode"),
                    heating_power: number(r, "heating_power_request"),
                    open_window: flag(r, "open_window"),
                })
            })
            .collect(),
        modules: list(home, "modules")
            .filter_map(|m| {
                Some(ModuleStatus {
                    id: text(m, "id")?,
                    reachable: flag(m, "reachable"),
                    battery_percent: battery_percent(m),
                    rf_strength: int(m, "rf_strength"),
                    wifi_strength: int(m, "wifi_strength"),
                    boiler: flag(m, "boiler_status"),
                })
            })
            .collect(),
    }
}

/// A weather station (`NAMain`), an air monitor (`NHC`) or one of a
/// station's modules (`NAModule1` outdoor, `2` wind, `3` rain, `4` indoor).
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Sensor {
    pub(crate) id: String,
    pub(crate) kind: String,
    pub(crate) name: String,
    pub(crate) reachable: Option<bool>,
    pub(crate) battery_percent: Option<i64>,
    /// Netatmo's signal figure: the lower, the better. `rf_status` for a
    /// module, `wifi_status` for a station.
    pub(crate) signal: Option<i64>,
    pub(crate) wifi: bool,
    /// `dashboard_data`: the last measures.
    pub(crate) dashboard: Json,
}

fn sensor(v: &Json, station: &str) -> Option<Sensor> {
    let id = text(v, "_id")?;
    let wifi = v.get("wifi_status").is_some();
    Some(Sensor {
        kind: text(v, "type").unwrap_or_default(),
        name: text(v, "module_name")
            .or_else(|| text(v, "station_name"))
            .unwrap_or_else(|| station.to_owned()),
        reachable: flag(v, "reachable"),
        battery_percent: int(v, "battery_percent"),
        signal: int(v, "wifi_status").or_else(|| int(v, "rf_status")),
        wifi,
        dashboard: v.get("dashboard_data").cloned().unwrap_or(Json::Null),
        id,
    })
}

/// `/api/getstationsdata` and `/api/gethomecoachsdata`: every device, then its modules.
pub(crate) fn parse_sensors(answer: &Json) -> Vec<Sensor> {
    let mut out = Vec::new();
    for device in list(&answer["body"], "devices") {
        let station = text(device, "station_name")
            .or_else(|| text(device, "name"))
            .unwrap_or_default();
        out.extend(sensor(device, &station));
        out.extend(list(device, "modules").filter_map(|m| sensor(m, &station)));
    }
    out
}

/// What a station shows, as Home Assistant words it, from Netatmo's figure.
pub(crate) fn signal_level(figure: i64, wifi: bool) -> &'static str {
    let (low, medium, high) = if wifi { (86, 71, 56) } else { (90, 76, 60) };
    if figure >= low {
        "low"
    } else if figure >= medium {
        "medium"
    } else if figure >= high {
        "high"
    } else {
        "full"
    }
}

/// An air monitor's health index (0 best), as Home Assistant words it.
pub(crate) fn health(index: i64) -> &'static str {
    match index {
        0 => "healthy",
        1 => "fine",
        2 => "fair",
        3 => "poor",
        _ => "unhealthy",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn homes() -> Json {
        serde_json::from_str(include_str!(
            "../../../integrations/netatmo/fixtures/energy-homesdata.json"
        ))
        .unwrap()
    }

    #[test]
    fn homesdata_gives_rooms_modules_and_heating_schedules() {
        let all = parse_homesdata(&homes(), None);
        assert_eq!(all.len(), 2);
        let home = &all[0];
        assert_eq!(home.name, "Maison");
        assert_eq!(home.therm_mode.as_deref(), Some("schedule"));
        assert_eq!(home.rooms.len(), 2);
        assert_eq!(home.rooms[0].module_ids, ["aa:bb:cc:dd:ee:02"]);
        assert_eq!(home.modules.len(), 3);
        assert_eq!(home.modules[1].kind, "NRV");
        assert_eq!(home.modules[1].room_id.as_deref(), Some("1"));
        // The cooling schedule is not offered.
        let names: Vec<_> = home.schedules.iter().map(|s| s.name.as_str()).collect();
        assert_eq!(names, ["Semaine", "Vacances"]);
        assert!(home.schedules[0].selected);
        assert!(home.has_heating());
        assert!(!all[1].has_heating());
    }

    #[test]
    fn homesdata_keeps_only_the_wanted_home() {
        let one = parse_homesdata(&homes(), Some("home-cameras"));
        assert_eq!(one.len(), 1);
        assert_eq!(one[0].id, "home-cameras");
        assert!(parse_homesdata(&homes(), Some("nope")).is_empty());
    }

    #[test]
    fn homestatus_gives_rooms_and_modules() {
        let answer: Json = serde_json::from_str(include_str!(
            "../../../integrations/netatmo/fixtures/energy-homestatus.json"
        ))
        .unwrap();
        let status = parse_homestatus(&answer);
        assert_eq!(status.therm_mode.as_deref(), Some("schedule"));
        assert_eq!(status.rooms.len(), 2);
        let salon = &status.rooms[0];
        assert_eq!(salon.measured, Some(20.4));
        assert_eq!(salon.setpoint, Some(19.0));
        assert_eq!(salon.mode.as_deref(), Some("home"));
        assert_eq!(salon.heating_power, Some(0.0));
        assert_eq!(salon.open_window, Some(false));
        let chambre = &status.rooms[1];
        assert_eq!(chambre.mode.as_deref(), Some("manual"));
        let valve = status
            .modules
            .iter()
            .find(|m| m.id == "aa:bb:cc:dd:ee:02")
            .unwrap();
        assert_eq!(valve.battery_percent, Some(75));
        assert_eq!(valve.rf_strength, Some(62));
        let relay = status
            .modules
            .iter()
            .find(|m| m.id == "aa:bb:cc:dd:ee:01")
            .unwrap();
        assert_eq!(relay.wifi_strength, Some(58));
        assert_eq!(relay.boiler, None);
        let thermostat = status
            .modules
            .iter()
            .find(|m| m.id == "aa:bb:cc:dd:ee:03")
            .unwrap();
        assert_eq!(thermostat.boiler, Some(false));
    }

    #[test]
    fn a_status_without_fields_is_empty_not_an_error() {
        assert_eq!(parse_homestatus(&Json::Null), HomeStatus::default());
    }

    #[test]
    fn stations_give_the_main_device_then_its_modules() {
        let answer: Json = serde_json::from_str(include_str!(
            "../../../integrations/netatmo/fixtures/weather-stationsdata.json"
        ))
        .unwrap();
        let sensors = parse_sensors(&answer);
        let kinds: Vec<_> = sensors.iter().map(|s| s.kind.as_str()).collect();
        assert_eq!(
            kinds,
            ["NAMain", "NAModule1", "NAModule2", "NAModule3", "NAModule4"]
        );
        assert_eq!(sensors[0].name, "Salon");
        assert!(sensors[0].wifi);
        assert_eq!(sensors[0].dashboard["CO2"], 612);
        assert_eq!(sensors[1].battery_percent, Some(78));
        assert!(!sensors[1].wifi);
        assert_eq!(sensors[2].dashboard["WindStrength"], 11);
    }

    #[test]
    fn signal_levels_follow_home_assistant() {
        assert_eq!(signal_level(95, false), "low");
        assert_eq!(signal_level(80, false), "medium");
        assert_eq!(signal_level(62, false), "high");
        assert_eq!(signal_level(40, false), "full");
        assert_eq!(signal_level(86, true), "low");
        assert_eq!(signal_level(55, true), "full");
        assert_eq!(health(0), "healthy");
        assert_eq!(health(9), "unhealthy");
    }

    #[test]
    fn the_battery_state_gives_a_percentage() {
        let m: Json = serde_json::json!({"battery_state": "high"});
        assert_eq!(battery_percent(&m), Some(75));
        let m: Json = serde_json::json!({"battery_percent": 41, "battery_state": "low"});
        assert_eq!(battery_percent(&m), Some(41));
        assert_eq!(battery_percent(&serde_json::json!({})), None);
    }
}
