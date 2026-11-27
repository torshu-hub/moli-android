//! The « Énergie » screen of the app (`EnergyActivity.java`): the official
//! `[energy]` section of `moli.toml` (`moli_energy::Config`), read and
//! written whole, held to the official crate's own check before it is
//! written; then the screen restarts Moli as the integrations screen does
//! (the previous file back if Moli refuses it). The points to choose from
//! are the house's, as `/api/devices` gives them.

use std::path::Path;

use anyhow::{Context as _, anyhow};
use moli_net::Method;
use serde::Deserialize;
use serde_json::{Value as Json, json};
use toml_edit::{ArrayOfTables, Item, Table};

use crate::plugs::Moli;
use crate::setup;

/// The section, as JSON (`{}` when the house has none).
pub fn read(config: &Path) -> anyhow::Result<String> {
    let doc = setup::read(config)?;
    let all: toml::Table = toml::from_str(&doc.to_string())?;
    let section = all
        .get("energy")
        .cloned()
        .unwrap_or_else(|| toml::Value::Table(toml::Table::new()));
    Ok(serde_json::to_string(&section)?)
}

/// Writes the whole section (`{}` takes it out), after the official check:
/// the other sections and their comments stay as they are.
pub fn put(config: &Path, body: &str) -> anyhow::Result<()> {
    let section: toml::Table = serde_json::from_str(body).context("invalid [energy]")?;
    let mut doc = setup::read(config)?;
    let decor = doc
        .get("energy")
        .and_then(Item::as_table)
        .map(|t| t.decor().clone());
    doc.remove("energy");
    if !section.is_empty() {
        let checked: moli_energy::Config = toml::Value::Table(section.clone())
            .try_into()
            .map_err(|e| anyhow!("[energy] {e}"))?;
        checked.check().map_err(|e| anyhow!("[energy] {e}"))?;
        let mut table = table(&section, true)?;
        if let Some(decor) = decor {
            *table.decor_mut() = decor;
        }
        doc.insert("energy", Item::Table(table));
    }
    setup::write(config, &doc)
}

/// The keys in the order a person reads them; the others after, by name.
const ORDER: &[&str] = &[
    "id",
    "name",
    "role",
    "point",
    "switch",
    "grid",
    "power_w",
    "integrate",
    "power",
    "max",
    "warn",
    "soc",
    "discharge_positive",
    "capacity_kwh",
    "reset",
    "within",
    "feeds",
    "estimate",
    "history",
    "period",
    "prices",
    "price",
    "price_point",
    "price_extra",
    "kwh_per_unit",
    "offpeak",
    "daily_minutes",
    "min_on_minutes",
    "min_off_minutes",
    "currency",
    "monthly_fee",
    "budget",
    "max_power_kw",
    "tariff",
    "live",
    "battery",
    "meter",
    "load",
];

/// `[energy]` and its tables (`[energy.tariff]`, `[[energy.meter]]`…);
/// deeper tables inline (`prices = { HP = 0.17 }`).
fn table(map: &toml::Table, top: bool) -> anyhow::Result<Table> {
    let mut keys: Vec<&String> = map.keys().collect();
    keys.sort_by_key(|k| {
        (
            ORDER.iter().position(|o| o == k).unwrap_or(ORDER.len()),
            k.as_str(),
        )
    });
    let mut out = Table::new();
    for key in keys {
        let value = &map[key];
        let item = match value {
            toml::Value::Table(inner) if top => Item::Table(table(inner, false)?),
            toml::Value::Array(list)
                if top && !list.is_empty() && list.iter().all(toml::Value::is_table) =>
            {
                let mut array = ArrayOfTables::new();
                for entry in list.iter().filter_map(toml::Value::as_table) {
                    array.push(table(entry, false)?);
                }
                Item::ArrayOfTables(array)
            }
            other => Item::Value(
                other
                    .to_string()
                    .parse()
                    .with_context(|| format!("[energy] {key}"))?,
            ),
        };
        out.insert(key, item);
    }
    Ok(out)
}

/// Where Moli listens and the house's code: a session, as the dashboard opens one.
#[derive(Deserialize)]
pub struct Session {
    pub port: u16,
    pub pin: String,
}

impl std::fmt::Debug for Session {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Session")
            .field("port", &self.port)
            .finish_non_exhaustive()
    }
}

/// The house's points to choose from, and the plugs that measure a power no
/// meter follows yet (see [`points_of`]).
pub fn points(config: &Path, request: &Session) -> anyhow::Result<Json> {
    let section: Json = serde_json::from_str(&read(config)?)?;
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    let snapshot = runtime.block_on(async {
        let moli = Moli::open(request.port, &request.pin).await?;
        let devices = moli.call(Method::GET, "/api/devices", &Json::Null).await;
        moli.close().await;
        let (status, snapshot) = devices?;
        anyhow::ensure!(status == 200, "Moli: HTTP {status}");
        Ok(snapshot)
    })?;
    Ok(points_of(&snapshot, &section))
}

/// What a point can be used for in `[energy]`.
fn family(point: &Json) -> &'static str {
    let unit = point["unit"].as_str().unwrap_or_default();
    let writable = point["access"]["write"].as_bool() == Some(true);
    match (point["kind"]["type"].as_str().unwrap_or_default(), unit) {
        ("binary", _) if writable => "switch",
        ("numeric", "W" | "kW" | "VA") => "power",
        ("numeric", "Wh" | "kWh" | "m³") => "energy",
        ("numeric", "%") => "percent",
        ("numeric", _) => "number",
        ("enum" | "text", _) => "text",
        _ => "other",
    }
}

/// `{"points": [{id, device, name, label, unit, family, value}], "suggested":
/// [{device, name, point}]}`: every point of the house but Moli's own
/// summaries (« Énergie », the driven loads); suggested, each device with a
/// power in W that no meter nor load names any point of (a plug to follow).
fn points_of(snapshot: &Json, section: &Json) -> Json {
    let mut named = Vec::new();
    for meter in section["meter"].as_array().into_iter().flatten() {
        for key in ["point", "power"] {
            named.extend(meter[key].as_str());
        }
        named.extend(
            meter["feeds"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Json::as_str),
        );
    }
    for load in section["load"].as_array().into_iter().flatten() {
        named.extend(["switch", "grid"].iter().filter_map(|k| load[k].as_str()));
    }
    let own = [moli_energy::points::INSTANCE, moli_energy::load::INSTANCE];
    let mut points = Vec::new();
    let mut suggested = Vec::new();
    for device in snapshot["devices"].as_array().into_iter().flatten() {
        let Some(id) = device["id"].as_str() else {
            continue;
        };
        if own.contains(&id.split(':').next().unwrap_or_default()) {
            continue;
        }
        let name = device["label"]["name"]
            .as_str()
            .or_else(|| device["native_name"].as_str())
            .unwrap_or(id);
        let followed = named
            .iter()
            .any(|n| *n == id || n.split('/').next() == Some(id));
        let mut power = None;
        for point in device["points"].as_array().into_iter().flatten() {
            let Some(key) = point["key"].as_str() else {
                continue;
            };
            let family = family(point);
            let label = device["label"]["points"][key]
                .as_str()
                .or_else(|| point["label"].as_str())
                .unwrap_or(key);
            let full = format!("{id}/{key}");
            if family == "power" && point["unit"] == "W" && (power.is_none() || key == "power") {
                power = Some(full.clone());
            }
            points.push(json!({
                "id": full,
                "device": id,
                "name": name,
                "label": label,
                "unit": point["unit"],
                "family": family,
                "value": device["state"][key]["value"],
            }));
        }
        if let Some(point) = power.filter(|_| !followed) {
            suggested.push(json!({ "device": id, "name": name, "point": point }));
        }
    }
    json!({ "points": points, "suggested": suggested })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Dir(std::path::PathBuf);
    impl Drop for Dir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn house(name: &str, text: &str) -> (Dir, std::path::PathBuf) {
        let dir = std::env::temp_dir().join(format!("moli-energy-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let config = dir.join("moli.toml");
        std::fs::write(&config, text).unwrap();
        (Dir(dir), config)
    }

    const PLUG: &str = r#"{"currency":"€","monthly_fee":19.5,
        "tariff":{"period":"linky:compteur/ptec","prices":{"HP":0.2,"HC":0.15}},
        "live":{"power":"linky:compteur/papp","max":9000},
        "meter":[{"id":"tele","name":"Télé","point":"meross-1:aaaa/power","role":"appliance","integrate":true}],
        "load":[{"id":"chauffe_eau","name":"Chauffe-eau","switch":"relais:r/on","grid":"pince:p/power","power_w":2400,"offpeak":["22:00-06:00"],"daily_minutes":120}]}"#;

    #[test]
    fn the_section_is_written_in_the_official_schema_and_the_rest_kept() {
        let (_dir, config) = house(
            "write",
            "# La maison.\n[server]\nlisten = \"0.0.0.0:8790\"\n\n# Le Zigbee.\n[[driver]]\nid = \"z2m\"\nkind = \"z2m\"\n",
        );
        assert_eq!(read(&config).unwrap(), "{}");
        put(&config, PLUG).unwrap();
        let text = std::fs::read_to_string(&config).unwrap();
        assert!(text.starts_with("# La maison.\n[server]\n"), "{text}");
        assert!(text.contains("# Le Zigbee.\n[[driver]]"), "{text}");
        assert!(
            text.contains("[[energy.meter]]\nid = \"tele\"\nname = \"Télé\"\nrole = \"appliance\"\npoint = \"meross-1:aaaa/power\"\nintegrate = true\n"),
            "{text}"
        );
        assert!(text.contains("prices = { HC = 0.15, HP = 0.2 }"), "{text}");
        assert!(
            text.contains("[energy.live]\npower = \"linky:compteur/papp\"\nmax = 9000\n"),
            "{text}"
        );
        assert!(text.contains("offpeak = [\"22:00-06:00\"]"), "{text}");
        // What the engine reads: the official schema, checked.
        let parsed: toml::Table = toml::from_str(&text).unwrap();
        let energy: moli_energy::Config = parsed["energy"].clone().try_into().unwrap();
        energy.check().unwrap();
        assert_eq!(energy.meters[0].role, moli_energy::config::Role::Appliance);
        assert!(energy.meters[0].integrate);
        assert_eq!(energy.loads.len(), 1);
        // Read back as written.
        let back: Json = serde_json::from_str(&read(&config).unwrap()).unwrap();
        assert_eq!(back["meter"][0]["point"], "meross-1:aaaa/power");
        assert_eq!(back["tariff"]["prices"]["HC"], 0.15);
        // Written again: the same text (the order is stable).
        put(&config, &back.to_string()).unwrap();
        assert_eq!(std::fs::read_to_string(&config).unwrap(), text);
        // Emptied: the section goes, the rest stays.
        put(&config, "{}").unwrap();
        let text = std::fs::read_to_string(&config).unwrap();
        assert!(!text.contains("energy"), "{text}");
        assert!(text.contains("[[driver]]\nid = \"z2m\""), "{text}");
    }

    #[test]
    fn what_the_official_check_refuses_is_never_written() {
        let (_dir, config) = house("refuse", "[server]\n");
        let before = std::fs::read_to_string(&config).unwrap();
        // A point that is not a point id, an unknown key, a price in cents.
        for bad in [
            r#"{"meter":[{"id":"x","name":"X","point":"power","role":"appliance"}]}"#,
            r#"{"meter":[{"id":"x","name":"X","point":"a:b/c","role":"appliance","colour":"red"}]}"#,
            r#"{"meter":[{"id":"x","name":"X","point":"a:b/c","role":"grid","price_point":"p:q/r","price_extra":12}]}"#,
            r#"{"meter":[{"id":"a b","name":"X","point":"a:b/c","role":"grid"}]}"#,
        ] {
            assert!(put(&config, bad).is_err(), "{bad}");
        }
        assert_eq!(std::fs::read_to_string(&config).unwrap(), before);
    }

    #[test]
    fn the_points_say_what_they_hold_and_plugs_not_followed_are_suggested() {
        let snapshot = json!({ "devices": [
            { "id": "meross-1:aaaa", "native_name": "Smart Plug", "label": { "name": "Télé" },
              "points": [
                { "key": "on", "label": "Marche", "kind": { "type": "binary" }, "access": { "read": true, "write": true } },
                { "key": "power", "label": "Puissance", "unit": "W", "kind": { "type": "numeric" }, "access": { "read": true } },
                { "key": "energy_today", "label": "Énergie du jour", "unit": "Wh", "kind": { "type": "numeric" }, "access": { "read": true } },
              ],
              "state": { "power": { "value": 52.5, "ts": 1 } } },
            { "id": "meross-2:bbbb", "native_name": "Smart Plug", "label": {},
              "points": [ { "key": "power", "label": "Puissance", "unit": "W", "kind": { "type": "numeric" }, "access": { "read": true } } ],
              "state": {} },
            { "id": "linky:compteur", "native_name": "Linky",
              "points": [
                { "key": "ptec", "label": "Période", "kind": { "type": "text" }, "access": { "read": true } },
                { "key": "papp", "label": "Puissance apparente", "unit": "VA", "kind": { "type": "numeric" }, "access": { "read": true } },
              ] },
            { "id": "energie:maison", "native_name": "Énergie",
              "points": [ { "key": "today_kwh", "unit": "kWh", "kind": { "type": "numeric" }, "access": { "read": true } } ] },
        ]});
        let section = json!({ "meter": [ { "id": "b", "point": "meross-2:bbbb/power" } ] });
        let out = points_of(&snapshot, &section);
        let points = out["points"].as_array().unwrap();
        let of = |id: &str| points.iter().find(|p| p["id"] == id).unwrap().clone();
        assert_eq!(of("meross-1:aaaa/on")["family"], "switch");
        assert_eq!(of("meross-1:aaaa/power")["family"], "power");
        assert_eq!(of("meross-1:aaaa/power")["value"], 52.5);
        assert_eq!(of("meross-1:aaaa/power")["name"], "Télé");
        assert_eq!(of("meross-1:aaaa/energy_today")["family"], "energy");
        assert_eq!(of("linky:compteur/ptec")["family"], "text");
        assert_eq!(of("linky:compteur/papp")["family"], "power");
        assert_eq!(of("meross-2:bbbb/power")["name"], "Smart Plug");
        assert!(points.iter().all(|p| p["device"] != "energie:maison"));
        // The plug already followed is not suggested again; a VA is no plug.
        assert_eq!(
            out["suggested"],
            json!([{ "device": "meross-1:aaaa", "name": "Télé", "point": "meross-1:aaaa/power" }])
        );
    }
}
