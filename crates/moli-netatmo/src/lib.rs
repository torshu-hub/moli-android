//! Netatmo through its cloud (`api.netatmo.com`): the heating (thermostats
//! and radiator valves, one device per room, one for the home with its
//! modes and schedules, one per module with its battery and signal), the
//! weather stations (every module's measures) and the air monitors.
//!
//! The connection is Home Assistant's: the person's own Netatmo application
//! (`client_id`, `client_secret`), a `refresh_token` obtained once and
//! renewed by the driver at every use ([`auth`]). Home Assistant also gets
//! pushes by webhook; Moli has no public address, so the driver polls, as
//! Home Assistant does without a webhook. The cameras are another driver
//! (the official PR #331 names the same secrets).
//!
//! The valve's points are Zigbee2MQTT's (`local_temperature`,
//! `occupied_heating_setpoint`, `system_mode`): the dashboard already shows
//! them as a valve.

mod auth;
mod model;

use std::collections::{HashMap, HashSet};
use std::fmt;
use std::time::Duration;

use anyhow::{Context as _, bail};
use http::{Method, Request, StatusCode};
use http_body_util::Full;
use moli_core::{Access, Device, DeviceId, InstanceId, Kind, PointSpec, Semantic, Unit, Value};
use moli_net::Body as Bytes;
use moli_runtime::{BoxFuture, CommandRequest, Driver, DriverCtx};
use serde::Deserialize;
use serde_json::Value as Json;
use tokio::time::{Instant, MissedTickBehavior};

use auth::Tokens;
use model::{Home, HomeStatus, Module, Room, RoomStatus, Schedule, Sensor};

const USER_AGENT: &str = "MoliOS/0.1";
const HTTP_LIMIT: Duration = Duration::from_secs(15);
const MAX_BODY: usize = 4 * 1024 * 1024;
/// Rooms and modules: every 30 seconds (about 120 calls an hour for a home,
/// against the 500 an hour Netatmo gives an account).
const POLL_STATUS: Duration = Duration::from_secs(30);
/// The homes' layout (rooms, modules, schedules).
const POLL_HOMES: Duration = Duration::from_secs(30 * 60);
/// The stations measure every ten minutes.
const POLL_SENSORS: Duration = Duration::from_secs(10 * 60);
/// After a refusal for too many calls, how long the driver stays quiet.
/// The setpoints a room takes (Home Assistant's `DEFAULT_MIN_TEMP`, `DEFAULT_MAX_TEMP`).
const MIN_TEMP: f64 = 7.0;
const MAX_TEMP: f64 = 30.0;
const BACKOFF: Duration = Duration::from_secs(5 * 60);

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    /// Only this home, if the account has several (all of them by default).
    #[serde(default)]
    pub home_id: Option<String>,
}

#[derive(Debug)]
pub struct Netatmo {
    config: Config,
}

impl Netatmo {
    #[must_use]
    pub fn new(config: Config) -> Self {
        Self { config }
    }
}

impl Driver for Netatmo {
    fn kind(&self) -> &'static str {
        "netatmo"
    }

    fn run<'a>(&'a self, ctx: &'a mut DriverCtx) -> BoxFuture<'a, anyhow::Result<()>> {
        Box::pin(run(&self.config, ctx))
    }
}

// ---------------------------------------------------------------- the API

/// Netatmo says the account made too many calls (HTTP 429, or error 26).
#[derive(Debug)]
struct Throttled;

impl fmt::Display for Throttled {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("netatmo: too many calls, waiting")
    }
}

impl std::error::Error for Throttled {}

async fn call(
    method: Method,
    path: &str,
    token: &str,
    form: Option<String>,
) -> anyhow::Result<Json> {
    let mut builder = Request::builder()
        .method(method)
        .uri(path)
        .header("user-agent", USER_AGENT)
        .header("authorization", format!("Bearer {token}"))
        .header("accept", "application/json");
    let payload = match form {
        Some(body) => {
            let raw = body.into_bytes();
            builder = builder
                .header(
                    "content-type",
                    "application/x-www-form-urlencoded;charset=UTF-8",
                )
                .header("content-length", raw.len().to_string());
            Bytes::from(raw)
        }
        None => Bytes::new(),
    };
    let request = builder.body(Full::new(payload))?;
    let (status, bytes) =
        moli_net::web(auth::HOST, 443, true, request, HTTP_LIMIT, MAX_BODY).await?;
    let json: Json = serde_json::from_slice(&bytes).unwrap_or(Json::Null);
    if status == StatusCode::TOO_MANY_REQUESTS || json["error"]["code"].as_i64() == Some(26) {
        return Err(Throttled.into());
    }
    if !status.is_success() {
        let why = json["error"]["message"].as_str().map_or_else(
            || String::from_utf8_lossy(&bytes[..bytes.len().min(160)]).into_owned(),
            str::to_owned,
        );
        bail!("{path}: HTTP {} {why}", status.as_u16());
    }
    Ok(json)
}

async fn get(path: &str, token: &str) -> anyhow::Result<Json> {
    call(Method::GET, path, token, None).await
}

/// A command: `status` must say `ok`.
async fn post(path: &str, token: &str, form: String) -> anyhow::Result<()> {
    let answer = call(Method::POST, path, token, Some(form)).await?;
    if answer["status"].as_str() == Some("ok") {
        Ok(())
    } else {
        bail!("{path}: {answer}")
    }
}

// ----------------------------------------------------------- the devices

/// Lowercase letters and digits of an id (a MAC address, a home's id).
fn alnum(id: &str) -> String {
    id.chars()
        .filter(char::is_ascii_alphanumeric)
        .map(|c| c.to_ascii_lowercase())
        .collect()
}

fn room_native(home: &str, room: &str) -> String {
    format!("room_{}_{}", alnum(home), alnum(room))
}

fn home_native(home: &str) -> String {
    format!("home_{}", alnum(home))
}

fn point(
    key: &str,
    label: &str,
    kind: Kind,
    write: bool,
    unit: Option<Unit>,
    semantic: Semantic,
) -> PointSpec {
    PointSpec {
        key: key.into(),
        label: label.into(),
        kind,
        access: Access { read: true, write },
        unit,
        semantic,
    }
}

fn degrees(min: f64, max: f64) -> Kind {
    Kind::Numeric {
        min: Some(min),
        max: Some(max),
        step: Some(0.5),
    }
}

fn choice(values: &[&str]) -> Kind {
    Kind::Enum {
        values: values.iter().map(|v| (*v).into()).collect(),
    }
}

fn plain() -> Kind {
    Kind::Numeric {
        min: None,
        max: None,
        step: None,
    }
}

/// The Netatmo model as its box says it.
fn model_name(kind: &str) -> Option<&'static str> {
    Some(match kind {
        "NAPlug" => "Smart Home Gateway",
        "NATherm1" => "Smart Thermostat",
        "OTM" => "Smart Modulating Thermostat",
        "NRV" => "Smart Radiator Valve",
        "NAMain" => "Weather Station",
        "NAModule1" => "Outdoor Module",
        "NAModule2" => "Wind Gauge",
        "NAModule3" => "Rain Gauge",
        "NAModule4" => "Indoor Module",
        "NHC" => "Healthy Home Coach",
        _ => return None,
    })
}

fn device(instance: &InstanceId, native: &str, name: &str, kind: Option<&str>) -> Device {
    Device {
        id: DeviceId::new(instance, native),
        instance: instance.clone(),
        native_name: name.into(),
        manufacturer: Some("Netatmo".into()),
        model: kind.and_then(model_name).map(Into::into),
        description: None,
        native_room: None,
        members: Vec::new(),
        points: Vec::new(),
    }
}

/// A room served by radiator valves only: they take no `off` and no `max`
/// mode (Home Assistant sets them manual at 7 and at 30 °C instead).
fn is_valve(home: &Home, room: &Room) -> bool {
    let kinds: Vec<&str> = room
        .module_ids
        .iter()
        .filter_map(|id| home.modules.iter().find(|m| &m.id == id))
        .map(|m| m.kind.as_str())
        .collect();
    kinds.contains(&"NRV") && !kinds.contains(&"NATherm1") && !kinds.contains(&"OTM")
}

fn room_device(instance: &InstanceId, home: &Home, room: &Room) -> Device {
    let kinds: Vec<&str> = room
        .module_ids
        .iter()
        .filter_map(|id| home.modules.iter().find(|m| &m.id == id))
        .map(|m| m.kind.as_str())
        .collect();
    let model = ["OTM", "NATherm1", "NRV"]
        .into_iter()
        .find(|k| kinds.contains(k));
    let mut d = device(
        instance,
        &room_native(&home.id, &room.id),
        &room.name,
        model,
    );
    d.description = Some(moli_i18n::tr!("pilotes.netatmo.piece").into());
    d.native_room = Some(room.name.as_str().into());
    d.points = vec![
        point(
            "local_temperature",
            &moli_i18n::tr!("pilotes.netatmo.temperature_piece"),
            plain(),
            false,
            Some(Unit::Celsius),
            Semantic::Temperature,
        ),
        point(
            "occupied_heating_setpoint",
            &moli_i18n::tr!("pilotes.netatmo.consigne"),
            degrees(MIN_TEMP, MAX_TEMP),
            true,
            Some(Unit::Celsius),
            Semantic::Temperature,
        ),
        point(
            "system_mode",
            &moli_i18n::tr!("pilotes.netatmo.mode"),
            if is_valve(home, room) {
                choice(&["auto", "heat"])
            } else {
                choice(&["auto", "heat", "off"])
            },
            true,
            None,
            Semantic::Config,
        ),
        point(
            "boost",
            &moli_i18n::tr!("pilotes.netatmo.boost"),
            Kind::Binary,
            true,
            None,
            Semantic::Config,
        ),
        point(
            "heat_demand",
            &moli_i18n::tr!("pilotes.netatmo.demande_chaleur"),
            Kind::Binary,
            false,
            None,
            Semantic::Other,
        ),
        point(
            "window_open",
            &moli_i18n::tr!("pilotes.netatmo.fenetre_ouverte"),
            Kind::Binary,
            false,
            None,
            Semantic::Other,
        ),
    ];
    d
}

/// What a schedule is called in the choice: its name, made unique if two share it.
fn schedule_labels(schedules: &[Schedule]) -> Vec<(String, String)> {
    let mut seen: HashSet<String> = HashSet::new();
    schedules
        .iter()
        .map(|s| {
            let mut label = s.name.clone();
            if !seen.insert(label.clone()) {
                label = format!("{} ({})", s.name, s.id);
                seen.insert(label.clone());
            }
            (s.id.clone(), label)
        })
        .collect()
}

fn home_device(instance: &InstanceId, home: &Home, labels: &[(String, String)]) -> Device {
    let mut d = device(instance, &home_native(&home.id), &home.name, None);
    d.description = Some(moli_i18n::tr!("pilotes.netatmo.maison").into());
    d.points = vec![point(
        "therm_mode",
        &moli_i18n::tr!("pilotes.netatmo.mode_maison"),
        choice(&["schedule", "away", "hg"]),
        true,
        None,
        Semantic::Config,
    )];
    if !labels.is_empty() {
        let names: Vec<&str> = labels.iter().map(|(_, l)| l.as_str()).collect();
        d.points.push(point(
            "schedule",
            &moli_i18n::tr!("pilotes.netatmo.programme"),
            choice(&names),
            true,
            None,
            Semantic::Config,
        ));
    }
    d
}

/// A heating module: its battery (those that have one), its signal, its boiler.
fn module_device(instance: &InstanceId, module: &Module) -> Device {
    let mut d = device(
        instance,
        &alnum(&module.id),
        &module.name,
        Some(&module.kind),
    );
    d.description = Some(moli_i18n::tr!("pilotes.netatmo.module").into());
    if matches!(module.kind.as_str(), "NRV" | "NATherm1") {
        d.points.push(battery_point());
    }
    if module.kind == "NAPlug" {
        d.points
            .push(signal_point("wifi_signal", "pilotes.netatmo.signal_wifi"));
    }
    d.points
        .push(signal_point("signal", "pilotes.netatmo.signal"));
    if matches!(module.kind.as_str(), "NATherm1" | "OTM") {
        d.points.push(point(
            "boiler",
            &moli_i18n::tr!("pilotes.netatmo.chaudiere"),
            Kind::Binary,
            false,
            None,
            Semantic::Other,
        ));
    }
    d
}

fn battery_point() -> PointSpec {
    point(
        "battery",
        &moli_i18n::tr!("pilotes.netatmo.batterie"),
        Kind::Numeric {
            min: Some(0.0),
            max: Some(100.0),
            step: None,
        },
        false,
        Some(Unit::Percent),
        Semantic::Battery,
    )
}

fn signal_point(key: &str, label: &str) -> PointSpec {
    point(
        key,
        &moli_i18n::tr(label),
        choice(&["low", "medium", "high", "full"]),
        false,
        None,
        Semantic::Other,
    )
}

/// A measure of a station: Netatmo's name, the point, its label, unit, meaning.
struct Measure {
    api: &'static str,
    key: &'static str,
    label: &'static str,
    unit: Option<Unit>,
    semantic: Semantic,
}

const fn m(
    api: &'static str,
    key: &'static str,
    label: &'static str,
    unit: Option<Unit>,
    semantic: Semantic,
) -> Measure {
    Measure {
        api,
        key,
        label,
        unit,
        semantic,
    }
}

static MEASURES: [Measure; 15] = [
    m(
        "Temperature",
        "temperature",
        "pilotes.netatmo.temperature",
        Some(Unit::Celsius),
        Semantic::Temperature,
    ),
    m(
        "min_temp",
        "min_temperature",
        "pilotes.netatmo.temperature_min",
        Some(Unit::Celsius),
        Semantic::Temperature,
    ),
    m(
        "max_temp",
        "max_temperature",
        "pilotes.netatmo.temperature_max",
        Some(Unit::Celsius),
        Semantic::Temperature,
    ),
    m(
        "Humidity",
        "humidity",
        "pilotes.netatmo.humidite",
        Some(Unit::Percent),
        Semantic::Humidity,
    ),
    m(
        "CO2",
        "co2",
        "pilotes.netatmo.co2",
        Some(Unit::Ppm),
        Semantic::Other,
    ),
    m(
        "Noise",
        "noise",
        "pilotes.netatmo.bruit",
        Some(Unit::Decibel),
        Semantic::Other,
    ),
    m(
        "Pressure",
        "pressure",
        "pilotes.netatmo.pression",
        Some(Unit::HectoPascal),
        Semantic::Pressure,
    ),
    m(
        "AbsolutePressure",
        "absolute_pressure",
        "pilotes.netatmo.pression_absolue",
        Some(Unit::HectoPascal),
        Semantic::Pressure,
    ),
    m(
        "Rain",
        "rain",
        "pilotes.netatmo.pluie",
        Some(Unit::Millimetre),
        Semantic::Other,
    ),
    m(
        "sum_rain_1",
        "rain_rate",
        "pilotes.netatmo.pluie_1h",
        Some(Unit::MillimetrePerHour),
        Semantic::RainRate,
    ),
    m(
        "sum_rain_24",
        "rain_24h",
        "pilotes.netatmo.pluie_24h",
        Some(Unit::Millimetre),
        Semantic::Other,
    ),
    m(
        "WindStrength",
        "wind_speed",
        "pilotes.netatmo.vent",
        Some(Unit::KilometrePerHour),
        Semantic::WindSpeed,
    ),
    m(
        "GustStrength",
        "wind_gust",
        "pilotes.netatmo.rafales",
        Some(Unit::KilometrePerHour),
        Semantic::WindGust,
    ),
    m(
        "WindAngle",
        "wind_direction",
        "pilotes.netatmo.direction_vent",
        Some(Unit::Degree),
        Semantic::WindDirection,
    ),
    m(
        "GustAngle",
        "gust_direction",
        "pilotes.netatmo.direction_rafales",
        Some(Unit::Degree),
        Semantic::Other,
    ),
];

const TRENDS: [(&str, &str, &str); 2] = [
    (
        "temp_trend",
        "temperature_trend",
        "pilotes.netatmo.tendance_temperature",
    ),
    (
        "pressure_trend",
        "pressure_trend",
        "pilotes.netatmo.tendance_pression",
    ),
];

/// A station or one of its modules: the measures it gives, its battery, its signal.
fn sensor_device(instance: &InstanceId, sensor: &Sensor) -> Device {
    let mut d = device(
        instance,
        &alnum(&sensor.id),
        &sensor.name,
        Some(&sensor.kind),
    );
    d.description = Some(moli_i18n::tr!("pilotes.netatmo.capteur").into());
    for m in &MEASURES {
        if sensor.dashboard.get(m.api).is_some() {
            d.points.push(point(
                m.key,
                &moli_i18n::tr(m.label),
                plain(),
                false,
                m.unit.clone(),
                m.semantic,
            ));
        }
    }
    for (api, key, label) in TRENDS {
        if sensor.dashboard.get(api).is_some() {
            d.points.push(point(
                key,
                &moli_i18n::tr(label),
                choice(&["up", "stable", "down"]),
                false,
                None,
                Semantic::Other,
            ));
        }
    }
    if sensor.dashboard.get("health_idx").is_some() {
        d.points.push(point(
            "health",
            &moli_i18n::tr!("pilotes.netatmo.sante"),
            choice(&["healthy", "fine", "fair", "poor", "unhealthy"]),
            false,
            None,
            Semantic::Other,
        ));
    }
    if sensor.battery_percent.is_some() {
        d.points.push(battery_point());
    }
    if sensor.signal.is_some() {
        d.points
            .push(signal_point("signal", "pilotes.netatmo.signal"));
    }
    d
}

// ------------------------------------------------------------- the state

/// Netatmo's setpoint mode of a room, as the valve's `system_mode`.
fn system_mode(netatmo: &str) -> &'static str {
    match netatmo {
        "off" => "off",
        "manual" | "max" => "heat",
        _ => "auto",
    }
}

/// A boost: the `max` mode, or for a valve (which has none) the highest setpoint.
fn boosted(mode: &str, setpoint: Option<f64>, valve: bool) -> bool {
    mode == "max" || (valve && mode == "manual" && setpoint.is_some_and(|t| t >= MAX_TEMP))
}

fn publish_room(ctx: &DriverCtx, id: &DeviceId, status: &RoomStatus, valve: bool) {
    ctx.set_availability(id, status.reachable.unwrap_or(true));
    if let Some(t) = status.measured {
        ctx.set_state(id, "local_temperature", Value::Float(t));
    }
    if let Some(t) = status.setpoint {
        ctx.set_state(id, "occupied_heating_setpoint", Value::Float(t));
    }
    if let Some(mode) = &status.mode {
        ctx.set_state(id, "system_mode", Value::Text(system_mode(mode).into()));
        ctx.set_state(
            id,
            "boost",
            Value::Bool(boosted(mode, status.setpoint, valve)),
        );
    }
    if let Some(power) = status.heating_power {
        ctx.set_state(id, "heat_demand", Value::Bool(power > 0.0));
    }
    if let Some(open) = status.open_window {
        ctx.set_state(id, "window_open", Value::Bool(open));
    }
}

fn publish_status(ctx: &DriverCtx, registry: &Registry, home_id: &str, status: &HomeStatus) {
    for room in &status.rooms {
        let id = ctx.device_id(&room_native(home_id, &room.id));
        if let Some(known) = registry.rooms.get(&id) {
            publish_room(ctx, &id, room, known.valve);
        }
    }
    for module in &status.modules {
        let id = ctx.device_id(&alnum(&module.id));
        if !registry.modules.contains(&id) {
            continue;
        }
        ctx.set_availability(&id, module.reachable.unwrap_or(true));
        if let Some(p) = module.battery_percent {
            ctx.set_state(&id, "battery", Value::Int(p));
        }
        if let Some(s) = module.rf_strength {
            ctx.set_state(
                &id,
                "signal",
                Value::Text(model::signal_level(s, false).into()),
            );
        }
        if let Some(s) = module.wifi_strength {
            ctx.set_state(
                &id,
                "wifi_signal",
                Value::Text(model::signal_level(s, true).into()),
            );
        }
        if let Some(on) = module.boiler {
            ctx.set_state(&id, "boiler", Value::Bool(on));
        }
    }
    if let Some(mode) = &status.therm_mode {
        let id = ctx.device_id(&home_native(home_id));
        if registry.homes.contains_key(&id) {
            ctx.set_state(&id, "therm_mode", Value::Text(mode.as_str().into()));
        }
    }
}

fn publish_sensor(ctx: &DriverCtx, sensor: &Sensor) {
    let id = ctx.device_id(&alnum(&sensor.id));
    ctx.set_availability(&id, sensor.reachable.unwrap_or(true));
    for m in &MEASURES {
        if let Some(n) = sensor.dashboard.get(m.api).and_then(Json::as_f64) {
            ctx.set_state(&id, m.key, Value::Float(n));
        }
    }
    for (api, key, _) in TRENDS {
        if let Some(t) = sensor.dashboard.get(api).and_then(Json::as_str) {
            ctx.set_state(&id, key, Value::Text(t.into()));
        }
    }
    if let Some(i) = sensor.dashboard.get("health_idx").and_then(Json::as_i64) {
        ctx.set_state(&id, "health", Value::Text(model::health(i).into()));
    }
    if let Some(p) = sensor.battery_percent {
        ctx.set_state(&id, "battery", Value::Int(p));
    }
    if let Some(s) = sensor.signal {
        ctx.set_state(
            &id,
            "signal",
            Value::Text(model::signal_level(s, sensor.wifi).into()),
        );
    }
}

// ---------------------------------------------------------- the registry

#[derive(Clone, Debug)]
struct RoomRef {
    home_id: String,
    room_id: String,
    /// Served by valves only (see [`is_valve`]).
    valve: bool,
}

#[derive(Clone, Debug)]
struct HomeRef {
    home_id: String,
    /// `(id, label as the choice shows it)`.
    schedules: Vec<(String, String)>,
}

/// What the driver published, to route the commands and clean up.
#[derive(Default)]
struct Registry {
    rooms: HashMap<DeviceId, RoomRef>,
    homes: HashMap<DeviceId, HomeRef>,
    modules: HashSet<DeviceId>,
    sensors: HashSet<DeviceId>,
    /// The last status of each room (a `heat` order keeps its setpoint).
    last: HashMap<DeviceId, RoomStatus>,
}

/// The homes' layout: published at start and every half hour.
async fn sync_homes(
    config: &Config,
    ctx: &DriverCtx,
    token: &str,
    registry: &mut Registry,
) -> anyhow::Result<()> {
    let answer = get("/api/homesdata", token).await?;
    let homes: Vec<Home> = model::parse_homesdata(&answer, config.home_id.as_deref())
        .into_iter()
        .filter(Home::has_heating)
        .collect();
    let mut next = Registry::default();
    for home in &homes {
        for room in &home.rooms {
            let d = room_device(ctx.instance(), home, room);
            next.rooms.insert(
                d.id.clone(),
                RoomRef {
                    home_id: home.id.clone(),
                    room_id: room.id.clone(),
                    valve: is_valve(home, room),
                },
            );
            ctx.upsert_device(d);
        }
        let labels = schedule_labels(&home.schedules);
        let d = home_device(ctx.instance(), home, &labels);
        let id = d.id.clone();
        ctx.upsert_device(d);
        if let Some(mode) = &home.therm_mode {
            ctx.set_state(&id, "therm_mode", Value::Text(mode.as_str().into()));
        }
        if let Some((_, label)) = home
            .schedules
            .iter()
            .zip(&labels)
            .find(|(s, _)| s.selected)
            .map(|(_, l)| l)
        {
            ctx.set_state(&id, "schedule", Value::Text(label.as_str().into()));
        }
        next.homes.insert(
            id,
            HomeRef {
                home_id: home.id.clone(),
                schedules: labels,
            },
        );
        for module in &home.modules {
            // A camera or a siren is not heating: the cameras are another driver.
            if matches!(module.kind.as_str(), "NAPlug" | "NATherm1" | "OTM" | "NRV") {
                let d = module_device(ctx.instance(), module);
                next.modules.insert(d.id.clone());
                ctx.upsert_device(d);
            }
        }
    }
    for gone in registry
        .rooms
        .keys()
        .chain(registry.homes.keys())
        .chain(registry.modules.iter())
    {
        let kept = next.rooms.contains_key(gone)
            || next.homes.contains_key(gone)
            || next.modules.contains(gone);
        if !kept {
            ctx.remove_device(gone);
        }
    }
    next.sensors = std::mem::take(&mut registry.sensors);
    next.last = std::mem::take(&mut registry.last);
    *registry = next;
    Ok(())
}

async fn poll_status(ctx: &DriverCtx, token: &str, registry: &mut Registry) -> anyhow::Result<()> {
    let homes: HashSet<String> = registry.homes.values().map(|h| h.home_id.clone()).collect();
    for home_id in homes {
        let path = format!(
            "/api/homestatus?{}",
            auth::form_encode(&[("home_id", &home_id)])
        );
        let answer = get(&path, token).await?;
        let status = model::parse_homestatus(&answer);
        publish_status(ctx, registry, &home_id, &status);
        for room in status.rooms {
            let id = ctx.device_id(&room_native(&home_id, &room.id));
            registry.last.insert(id, room);
        }
    }
    Ok(())
}

/// The stations and the air monitors: absent from the account is not an error.
async fn poll_sensors(ctx: &DriverCtx, token: &str, registry: &mut Registry) -> anyhow::Result<()> {
    let mut found = Vec::new();
    let mut failure = None;
    for path in ["/api/getstationsdata", "/api/gethomecoachsdata"] {
        match get(path, token).await {
            Ok(answer) => found.extend(model::parse_sensors(&answer)),
            Err(e) if e.is::<Throttled>() => return Err(e),
            Err(e) => failure = Some(e),
        }
    }
    let mut seen = HashSet::new();
    for sensor in &found {
        let d = sensor_device(ctx.instance(), sensor);
        seen.insert(d.id.clone());
        ctx.upsert_device(d);
        publish_sensor(ctx, sensor);
    }
    // Only when both answered: a failed call is not an account without stations.
    if failure.is_none() {
        for gone in registry.sensors.difference(&seen) {
            ctx.remove_device(gone);
        }
        registry.sensors = seen;
    } else {
        registry.sensors.extend(seen);
    }
    failure.map_or(Ok(()), Err)
}

// ------------------------------------------------------------ the orders

/// What an order asks of Netatmo.
#[derive(Debug, PartialEq)]
enum Action {
    /// `setroomthermpoint`: a mode of the room, with a setpoint for `manual`.
    Room {
        mode: &'static str,
        temperature: Option<f64>,
    },
    /// `setthermmode`: the home's mode.
    Home(&'static str),
    /// `switchhomeschedule`.
    Schedule(String),
}

fn half_degree(t: f64) -> f64 {
    (t * 2.0).round() / 2.0
}

fn plan_room(
    key: &str,
    value: &Value,
    last: Option<&RoomStatus>,
    valve_room: bool,
) -> anyhow::Result<Action> {
    match (key, value) {
        ("occupied_heating_setpoint", v) => Ok(Action::Room {
            mode: "manual",
            temperature: Some(half_degree(
                v.as_f64()
                    .context("not a temperature")?
                    .clamp(MIN_TEMP, MAX_TEMP),
            )),
        }),
        ("system_mode", Value::Text(t)) => match &**t {
            "auto" => Ok(Action::Room {
                mode: "home",
                temperature: None,
            }),
            "off" if !valve_room => Ok(Action::Room {
                mode: "off",
                temperature: None,
            }),
            // Keep the room at the setpoint it has, as a hand on the valve would.
            "heat" => {
                let held = last
                    .and_then(|s| s.setpoint)
                    .context(moli_i18n::tr!("pilotes.netatmo.consigne_inconnue"))?;
                Ok(Action::Room {
                    mode: "manual",
                    temperature: Some(half_degree(held)),
                })
            }
            other => bail!(moli_i18n::tr!(
                "pilotes.netatmo.valeur_inconnue",
                value = other
            )),
        },
        // A valve has no `max` mode: the highest setpoint is its boost.
        ("boost", Value::Bool(true)) if valve_room => Ok(Action::Room {
            mode: "manual",
            temperature: Some(MAX_TEMP),
        }),
        ("boost", Value::Bool(on)) => Ok(Action::Room {
            mode: if *on { "max" } else { "home" },
            temperature: None,
        }),
        _ => bail!(moli_i18n::tr!("pilotes.netatmo.point_inconnu", point = key)),
    }
}

fn plan_home(key: &str, value: &Value, schedules: &[(String, String)]) -> anyhow::Result<Action> {
    match (key, value) {
        ("therm_mode", Value::Text(t)) => match &**t {
            "schedule" => Ok(Action::Home("schedule")),
            "away" => Ok(Action::Home("away")),
            "hg" => Ok(Action::Home("hg")),
            other => bail!(moli_i18n::tr!(
                "pilotes.netatmo.valeur_inconnue",
                value = other
            )),
        },
        ("schedule", Value::Text(label)) => schedules
            .iter()
            .find(|(_, l)| l.as_str() == &**label)
            .map(|(id, _)| Action::Schedule(id.clone()))
            .with_context(|| moli_i18n::tr!("pilotes.netatmo.valeur_inconnue", value = &**label)),
        _ => bail!(moli_i18n::tr!("pilotes.netatmo.point_inconnu", point = key)),
    }
}

/// The path and the form of the call that carries out `action`.
fn request_of(action: &Action, home_id: &str, room_id: Option<&str>) -> (&'static str, String) {
    match action {
        Action::Room { mode, temperature } => {
            let temp = temperature.map(|t| format!("{t}"));
            let mut pairs = vec![
                ("home_id", home_id),
                ("room_id", room_id.unwrap_or_default()),
                ("mode", mode),
            ];
            if let Some(t) = &temp {
                pairs.push(("temp", t));
            }
            ("/api/setroomthermpoint", auth::form_encode(&pairs))
        }
        Action::Home(mode) => (
            "/api/setthermmode",
            auth::form_encode(&[("home_id", home_id), ("mode", mode)]),
        ),
        Action::Schedule(id) => (
            "/api/switchhomeschedule",
            auth::form_encode(&[("home_id", home_id), ("schedule_id", id)]),
        ),
    }
}

async fn handle_command(
    ctx: &DriverCtx,
    token: &str,
    registry: &mut Registry,
    cmd: CommandRequest,
) {
    let id = cmd.device.id.clone();
    let key = cmd.key.clone();
    let value = cmd.value.clone();
    let mut on_valve = false;
    let outcome: anyhow::Result<()> = async {
        let (action, home_id, room_id) = if let Some(room) = registry.rooms.get(&id) {
            on_valve = room.valve;
            (
                plan_room(&key, &value, registry.last.get(&id), room.valve)?,
                room.home_id.clone(),
                Some(room.room_id.clone()),
            )
        } else if let Some(home) = registry.homes.get(&id) {
            (
                plan_home(&key, &value, &home.schedules)?,
                home.home_id.clone(),
                None,
            )
        } else {
            bail!(moli_i18n::tr!("pilotes.netatmo.appareil_inconnu"));
        };
        let (path, form) = request_of(&action, &home_id, room_id.as_deref());
        post(path, token, form).await?;
        // Shown at once; the next reading says what Netatmo made of it.
        match &action {
            Action::Room { mode, temperature } => {
                if let Some(t) = temperature {
                    ctx.set_state(&id, "occupied_heating_setpoint", Value::Float(*t));
                }
                ctx.set_state(&id, "system_mode", Value::Text(system_mode(mode).into()));
                ctx.set_state(
                    &id,
                    "boost",
                    Value::Bool(boosted(mode, *temperature, on_valve)),
                );
            }
            Action::Home(mode) => ctx.set_state(&id, "therm_mode", Value::Text((*mode).into())),
            Action::Schedule(_) => ctx.set_state(&id, "schedule", value.clone()),
        }
        Ok(())
    }
    .await;
    match outcome {
        Ok(()) => cmd.reply(Ok(())),
        Err(e) => cmd.reply(Err(moli_i18n::tr!(
            "pilotes.netatmo.echec",
            why = format!("{e:#}")
        ))),
    }
}

// ----------------------------------------------------------- the driver

/// A valid token, or what to do about it: `Ok(None)` ends the driver (the
/// connection is revoked, said on the card).
async fn token_of(tokens: &mut Tokens, ctx: &DriverCtx) -> anyhow::Result<Option<String>> {
    tokens.access(ctx).await
}

fn revoked(ctx: &DriverCtx) {
    ctx.wait_for(moli_i18n::tr!(
        "pilotes.netatmo.connexion_revoquee",
        instance = ctx.instance()
    ));
}

/// A failed reading is told and the loop goes on; too many calls make it wait.
fn note(ctx: &DriverCtx, what: &str, err: &anyhow::Error, blocked: &mut Option<Instant>) {
    if err.is::<Throttled>() {
        tracing::warn!(instance = %ctx.instance(), "netatmo: too many calls, quiet for {BACKOFF:?}");
        *blocked = Some(Instant::now() + BACKOFF);
    } else {
        tracing::warn!(instance = %ctx.instance(), error = %format!("{err:#}"), "netatmo {what} failed");
    }
}

async fn run(config: &Config, ctx: &mut DriverCtx) -> anyhow::Result<()> {
    let Some(mut tokens) = Tokens::load(ctx) else {
        ctx.wait_for(moli_i18n::tr!(
            "pilotes.netatmo.identifiants_manquants",
            instance = ctx.instance(),
            missing = Tokens::missing(ctx)
        ));
        ctx.cancelled().await;
        return Ok(());
    };
    let mut registry = Registry::default();
    let mut blocked: Option<Instant> = None;

    match token_of(&mut tokens, ctx).await {
        Ok(Some(token)) => {
            if let Err(e) = sync_homes(config, ctx, &token, &mut registry).await {
                note(ctx, "home sync", &e, &mut blocked);
            }
            if let Err(e) = poll_status(ctx, &token, &mut registry).await {
                note(ctx, "status", &e, &mut blocked);
            }
            if let Err(e) = poll_sensors(ctx, &token, &mut registry).await {
                note(ctx, "sensors", &e, &mut blocked);
            }
        }
        Ok(None) => {
            revoked(ctx);
            ctx.cancelled().await;
            return Ok(());
        }
        Err(e) => note(ctx, "token renewal", &e, &mut blocked),
    }
    ctx.ready();

    let tick = |every| {
        let mut t = tokio::time::interval(every);
        t.set_missed_tick_behavior(MissedTickBehavior::Delay);
        t
    };
    let (mut status_tick, mut homes_tick, mut sensors_tick) =
        (tick(POLL_STATUS), tick(POLL_HOMES), tick(POLL_SENSORS));
    for t in [&mut status_tick, &mut homes_tick, &mut sensors_tick] {
        t.tick().await;
    }

    loop {
        tokio::select! {
            _ = status_tick.tick() => {
                if blocked.is_some_and(|until| Instant::now() < until) { continue; }
                match token_of(&mut tokens, ctx).await {
                    Ok(Some(token)) => if let Err(e) = poll_status(ctx, &token, &mut registry).await {
                        note(ctx, "status", &e, &mut blocked);
                    },
                    Ok(None) => { revoked(ctx); ctx.cancelled().await; return Ok(()); }
                    Err(e) => note(ctx, "token renewal", &e, &mut blocked),
                }
            }
            _ = homes_tick.tick() => {
                if blocked.is_some_and(|until| Instant::now() < until) { continue; }
                match token_of(&mut tokens, ctx).await {
                    Ok(Some(token)) => if let Err(e) = sync_homes(config, ctx, &token, &mut registry).await {
                        note(ctx, "home sync", &e, &mut blocked);
                    },
                    Ok(None) => { revoked(ctx); ctx.cancelled().await; return Ok(()); }
                    Err(e) => note(ctx, "token renewal", &e, &mut blocked),
                }
            }
            _ = sensors_tick.tick() => {
                if blocked.is_some_and(|until| Instant::now() < until) { continue; }
                match token_of(&mut tokens, ctx).await {
                    Ok(Some(token)) => if let Err(e) = poll_sensors(ctx, &token, &mut registry).await {
                        note(ctx, "sensors", &e, &mut blocked);
                    },
                    Ok(None) => { revoked(ctx); ctx.cancelled().await; return Ok(()); }
                    Err(e) => note(ctx, "token renewal", &e, &mut blocked),
                }
            }
            cmd = ctx.next_command() => {
                let Some(cmd) = cmd else { return Ok(()) };
                match token_of(&mut tokens, ctx).await {
                    Ok(Some(token)) => handle_command(ctx, &token, &mut registry, cmd).await,
                    Ok(None) => {
                        cmd.reply(Err(moli_i18n::tr!("pilotes.netatmo.connexion_revoquee", instance = ctx.instance())));
                        revoked(ctx);
                        ctx.cancelled().await;
                        return Ok(());
                    }
                    Err(e) => cmd.reply(Err(moli_i18n::tr!("pilotes.netatmo.echec", why = format!("{e:#}")))),
                }
            }
        }
    }
}

#[cfg(test)]
mod tests;
