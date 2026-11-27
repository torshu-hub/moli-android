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
use toml_edit::{ArrayOfTables, DocumentMut, Item, Table};

use crate::plugs::Moli;
use crate::setup;

/// The section and the house's off-peak hours, as JSON: `{"section": {…}
/// (`{}` when the house has none), "offpeak": ["22:00-06:00"] (empty: no
/// hours), "offpeak_point": the period they give, or null}`.
pub fn read(config: &Path) -> anyhow::Result<String> {
    let doc = setup::read(config)?;
    let all: toml::Table = toml::from_str(&doc.to_string())?;
    let section = all
        .get("energy")
        .cloned()
        .unwrap_or_else(|| toml::Value::Table(toml::Table::new()));
    let hours = hours_block(&doc);
    Ok(json!({
        "section": section,
        "offpeak": hours.as_ref().map(|(_, h)| h.clone()).unwrap_or_default(),
        "offpeak_point": hours.map(|(id, _)| period_point(&id)),
    })
    .to_string())
}

/// What the screen writes: the whole section, and the off-peak hours
/// (absent: left as they are; empty: taken out).
#[derive(Debug, Deserialize)]
struct Put {
    section: toml::Table,
    #[serde(default)]
    offpeak: Option<Vec<String>>,
}

/// The package of off-peak hours by the clock, and the first id it takes.
const HOURS: &str = "heures-creuses";
const HOURS_ID: &str = "tarif";

/// The point the hours of the block `id` give.
fn period_point(id: &str) -> String {
    format!("{id}:{}/{}", moli_offpeak::DEVICE, moli_offpeak::KEY)
}

/// The house's block of off-peak hours (`kind = "offpeak"`): its id, its hours.
fn hours_block(doc: &DocumentMut) -> Option<(String, Vec<String>)> {
    let block = setup::blocks(doc)?
        .iter()
        .find(|b| b.get("kind").and_then(Item::as_str) == Some("offpeak"))?;
    let id = block.get("id").and_then(Item::as_str)?.to_owned();
    let hours = block
        .get("options")
        .and_then(|o| o.get("offpeak"))
        .and_then(Item::as_array)
        .map(|a| {
            a.iter()
                .filter_map(|v| v.as_str().map(str::to_owned))
                .collect()
        })
        .unwrap_or_default();
    Some((id, hours))
}

/// Writes the whole section (`{}` takes it out) and the off-peak hours, in
/// one write, after the official checks: the other sections and their
/// comments stay as they are. Hours given and no period point named: the
/// tariff follows them.
pub fn put(config: &Path, body: &str) -> anyhow::Result<()> {
    let doc = changed(&setup::read(config)?, body)?;
    setup::write(config, &doc)
}

/// « Voir ce qui sera écrit »: the house's `[energy]` and its block of
/// off-peak hours, now and once `body` is written, read only; `error`: why
/// Moli's check would refuse it (nothing is written either way).
pub fn preview(config: &Path, body: &str) -> anyhow::Result<Json> {
    let doc = setup::read(config)?;
    let (after, error) = match changed(&doc, body) {
        Ok(after) => (excerpt(&after), Json::Null),
        Err(e) => (String::new(), json!(format!("{e:#}"))),
    };
    Ok(json!({ "before": excerpt(&doc), "after": after, "error": error }))
}

/// The house's file with `body` applied, held to the official checks.
fn changed(doc: &DocumentMut, body: &str) -> anyhow::Result<DocumentMut> {
    let Put {
        mut section,
        offpeak,
    } = serde_json::from_str(body).context("invalid [energy]")?;
    let mut doc = doc.clone();
    if let Some(hours) = offpeak {
        put_hours(&mut doc, &mut section, hours)?;
    }
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
    Ok(doc)
}

/// What this screen writes in the file: its block of off-peak hours, then `[energy]`.
fn excerpt(doc: &DocumentMut) -> String {
    let mut out = String::new();
    if let Some((id, _)) = hours_block(doc)
        && let Some(block) = setup::blocks(doc)
            .into_iter()
            .flat_map(ArrayOfTables::iter)
            .find(|b| b.get("id").and_then(Item::as_str) == Some(id.as_str()))
    {
        let mut part = DocumentMut::new();
        let mut list = ArrayOfTables::new();
        let mut block = block.clone();
        block.decor_mut().clear();
        list.push(block);
        part.insert("driver", Item::ArrayOfTables(list));
        out.push_str(part.to_string().trim());
        out.push_str("\n\n");
    }
    if let Some(energy) = doc.get("energy") {
        let mut part = DocumentMut::new();
        part.insert("energy", energy.clone());
        out.push_str(part.to_string().trim());
        out.push('\n');
    }
    out
}

/// The block of off-peak hours, as the integrations screen writes it.
fn put_hours(
    doc: &mut DocumentMut,
    section: &mut toml::Table,
    hours: Vec<String>,
) -> anyhow::Result<()> {
    let held = hours_block(doc).map(|(id, _)| id);
    let hours: Vec<String> = hours
        .into_iter()
        .map(|h| h.trim().to_owned())
        .filter(|h| !h.is_empty())
        .collect();
    if hours.is_empty() {
        if let Some(id) = held {
            setup::remove_block(doc, &id)?;
        }
        return Ok(());
    }
    moli_offpeak::OffPeak::new(moli_offpeak::Config {
        offpeak: hours.clone(),
    })
    .map_err(|e| anyhow!("heures creuses : {e}"))?;
    let taken: Vec<String> = setup::blocks(doc)
        .into_iter()
        .flat_map(ArrayOfTables::iter)
        .filter_map(|b| b.get("id").and_then(Item::as_str).map(str::to_owned))
        .collect();
    // Ids are finite: one of the first `taken.len() + 1` is free.
    let id = held.clone().unwrap_or_else(|| {
        (1..=taken.len() + 1)
            .map(|n| {
                if n == 1 {
                    HOURS_ID.to_owned()
                } else {
                    format!("{HOURS_ID}_{n}")
                }
            })
            .find(|id| !taken.contains(id))
            .unwrap_or_default()
    });
    let change: setup::Change = serde_json::from_value(json!({
        "previous": held,
        "id": id,
        "integration": HOURS,
        "fields": { "offpeak": toml::Value::Array(hours.iter().cloned().map(toml::Value::String).collect()).to_string() },
    }))?;
    setup::put_block(doc, &change)?;
    if let Some(tariff) = section
        .get_mut("tariff")
        .and_then(toml::Value::as_table_mut)
    {
        let named = tariff
            .get("period")
            .and_then(toml::Value::as_str)
            .is_some_and(|p| !p.trim().is_empty());
        if !named {
            tariff.insert("period".into(), toml::Value::String(period_point(&id)));
        }
    }
    Ok(())
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

/// The house's points to choose from, what measures (see [`points_of`]), and
/// the house's price of a kWh (`house_price`, null without one).
pub fn points(config: &Path, request: &Session) -> anyhow::Result<Json> {
    let section: Json = serde_json::from_str(&read(config)?)?;
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    let (snapshot, summary) = runtime.block_on(async {
        let moli = Moli::open(request.port, &request.pin).await?;
        let devices = moli.call(Method::GET, "/api/devices", &Json::Null).await;
        // No meter yet: 404, no price either.
        let summary = moli.call(Method::GET, "/api/energy", &Json::Null).await;
        moli.close().await;
        let (status, snapshot) = devices?;
        anyhow::ensure!(status == 200, "Moli: HTTP {status}");
        Ok((
            snapshot,
            summary.ok().filter(|(s, _)| *s == 200).map(|(_, j)| j),
        ))
    })?;
    let mut out = points_of(&snapshot, &section["section"]);
    out["house_price"] = summary.map_or(Json::Null, |s| s["house_price"].clone());
    Ok(out)
}

/// « Prix unique »: the house's price of a kWh, as the dashboard's card sets
/// it (`PUT /api/energy/price`, journaled, from now on). `Ok("")` when done;
/// else Moli's refusal.
#[derive(Deserialize)]
pub struct Price {
    pub port: u16,
    pub pin: String,
    pub price: f64,
}

impl std::fmt::Debug for Price {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Price")
            .field("price", &self.price)
            .finish_non_exhaustive()
    }
}

pub fn price(request: &Price) -> anyhow::Result<String> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    runtime.block_on(async {
        let moli = Moli::open(request.port, &request.pin).await?;
        let answer = moli
            .call(
                Method::PUT,
                "/api/energy/price",
                &json!({ "price": request.price }),
            )
            .await;
        moli.close().await;
        Ok(match answer? {
            (200, _) => String::new(),
            (status, body) => body["error"]
                .as_str()
                .map_or_else(|| format!("HTTP {status}"), str::to_owned),
        })
    })
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

/// What a device that measures most likely is, from what it says of itself.
fn guess(id: &str, device: &Json) -> &'static str {
    let said = format!(
        "{id} {} {}",
        device["model"].as_str().unwrap_or_default(),
        device["native_name"].as_str().unwrap_or_default()
    )
    .to_lowercase();
    if said.contains("linky") || said.contains("enedis") {
        "grid"
    } else if said.contains("solar") || said.contains("onduleur") || said.contains("inverter") {
        "solar"
    } else if said.contains("3em") || said.contains("em06") || said.contains("pince") {
        "total"
    } else {
        "appliance"
    }
}

/// `{"points": [{id, device, name, label, unit, family, value}], "measuring":
/// [{device, name, room, point, family, value, unit, guess, followed}]}`:
/// every point of the house but Moli's own summaries (« Énergie », the
/// driven loads); measuring, each device with a power (W, kW) or an energy
/// counter (Wh, kWh, m³), its power first (Moli adds it up), what it most
/// likely is, and whether a meter or a load already names it.
fn points_of(snapshot: &Json, section: &Json) -> Json {
    let mut named = Vec::new();
    for meter in section["meter"].as_array().into_iter().flatten() {
        for key in ["point", "power"] {
            named.extend(meter[key].as_str());
        }
    }
    for load in section["load"].as_array().into_iter().flatten() {
        named.extend(["switch", "grid"].iter().filter_map(|k| load[k].as_str()));
    }
    let own = [moli_energy::points::INSTANCE, moli_energy::load::INSTANCE];
    let mut points = Vec::new();
    let mut measuring = Vec::new();
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
        let followed = named.iter().any(|n| n.split('/').next() == Some(id));
        // (rank, point): a power in W first, the key `power` before the others.
        let mut best: Option<(u8, Json)> = None;
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
            let value = device["state"][key]["value"].clone();
            let rank = match (family, point["unit"].as_str().unwrap_or_default()) {
                ("power", "W") if key == "power" => Some(0),
                ("power", "W" | "kW") => Some(1),
                ("energy", _) => Some(2),
                _ => None,
            };
            if let Some(rank) = rank
                && best.as_ref().is_none_or(|(r, _)| rank < *r)
            {
                best = Some((
                    rank,
                    json!({
                        "device": id,
                        "name": name,
                        "room": device["label"]["room"].as_str().or_else(|| device["native_room"].as_str()),
                        "point": full,
                        "family": family,
                        "value": value,
                        "unit": point["unit"],
                        "guess": guess(id, device),
                        "followed": followed,
                    }),
                ));
            }
            points.push(json!({
                "id": full,
                "device": id,
                "name": name,
                "label": label,
                "unit": point["unit"],
                "family": family,
                "value": value,
            }));
        }
        measuring.extend(best.map(|(_, m)| m));
    }
    json!({ "points": points, "measuring": measuring })
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
        let empty: Json = serde_json::from_str(&read(&config).unwrap()).unwrap();
        assert_eq!(
            empty,
            json!({ "section": {}, "offpeak": [], "offpeak_point": null })
        );
        put(&config, &format!("{{\"section\":{PLUG}}}")).unwrap();
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
        assert_eq!(back["section"]["meter"][0]["point"], "meross-1:aaaa/power");
        assert_eq!(back["section"]["tariff"]["prices"]["HC"], 0.15);
        // Written again: the same text (the order is stable).
        put(&config, &json!({ "section": back["section"] }).to_string()).unwrap();
        assert_eq!(std::fs::read_to_string(&config).unwrap(), text);
        // Emptied: the section goes, the rest stays.
        put(&config, r#"{"section":{}}"#).unwrap();
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
            assert!(
                put(&config, &format!("{{\"section\":{bad}}}")).is_err(),
                "{bad}"
            );
        }
        assert_eq!(std::fs::read_to_string(&config).unwrap(), before);
    }

    #[test]
    fn off_peak_hours_become_a_block_the_tariff_follows() {
        let (_dir, config) = house(
            "hours",
            "[server]\n\n[[driver]]\nid = \"tarif\"\nkind = \"z2m\"\n",
        );
        let prices = json!({ "tariff": { "prices": { "HP": 0.2081, "HC": 0.1635 } } });
        put(
            &config,
            &json!({ "section": prices, "offpeak": ["22:00-06:00", " 12:30-14:30 "] }).to_string(),
        )
        .unwrap();
        let text = std::fs::read_to_string(&config).unwrap();
        // « tarif » is taken: the next id, in the official schema of the package.
        assert!(
            text.contains("[[driver]]\nid = \"tarif_2\"\nkind = \"offpeak\"\n\n[driver.options]\noffpeak = [\"22:00-06:00\", \"12:30-14:30\"]\n"),
            "{text}"
        );
        assert!(
            text.contains("period = \"tarif_2:periode/period\""),
            "{text}"
        );
        let back: Json = serde_json::from_str(&read(&config).unwrap()).unwrap();
        assert_eq!(back["offpeak"], json!(["22:00-06:00", "12:30-14:30"]));
        assert_eq!(back["offpeak_point"], "tarif_2:periode/period");
        // Changed: the same block. A Linky's period named: kept as it is.
        let linky = json!({ "tariff": { "period": "linky:compteur/ptec", "prices": { "HP": 0.2, "HC": 0.15 } } });
        put(
            &config,
            &json!({ "section": linky, "offpeak": ["23:00-07:00"] }).to_string(),
        )
        .unwrap();
        let text = std::fs::read_to_string(&config).unwrap();
        assert_eq!(text.matches("kind = \"offpeak\"").count(), 1, "{text}");
        assert!(text.contains("offpeak = [\"23:00-07:00\"]"), "{text}");
        assert!(text.contains("period = \"linky:compteur/ptec\""), "{text}");
        // Bad hours: nothing written.
        let before = std::fs::read_to_string(&config).unwrap();
        assert!(
            put(
                &config,
                &json!({ "section": linky, "offpeak": ["22h-6h"] }).to_string()
            )
            .is_err()
        );
        assert_eq!(std::fs::read_to_string(&config).unwrap(), before);
        // Taken out: the block goes, the other one stays.
        put(
            &config,
            &json!({ "section": linky, "offpeak": [] }).to_string(),
        )
        .unwrap();
        let text = std::fs::read_to_string(&config).unwrap();
        assert!(!text.contains("offpeak"), "{text}");
        assert!(text.contains("id = \"tarif\"\nkind = \"z2m\""), "{text}");
    }

    #[test]
    fn the_preview_shows_before_and_after_and_writes_nothing() {
        let (_dir, config) = house(
            "preview",
            "[server]\n\n[[driver]]\nid = \"z2m\"\nkind = \"z2m\"\n",
        );
        let before = std::fs::read_to_string(&config).unwrap();
        let body = json!({
            "section": { "tariff": { "prices": { "HP": 0.2081, "HC": 0.1635 } } },
            "offpeak": ["22:00-06:00"],
        });
        let seen = preview(&config, &body.to_string()).unwrap();
        assert_eq!(seen["before"], "");
        let after = seen["after"].as_str().unwrap();
        assert!(
            after.starts_with("[[driver]]\nid = \"tarif\"\nkind = \"offpeak\""),
            "{after}"
        );
        assert!(
            after.contains("[energy.tariff]\nperiod = \"tarif:periode/period\""),
            "{after}"
        );
        assert!(
            !after.contains("z2m"),
            "only what this screen writes: {after}"
        );
        assert_eq!(seen["error"], Json::Null);
        assert_eq!(std::fs::read_to_string(&config).unwrap(), before);
        // Once written, « before » is what the file holds.
        put(&config, &body.to_string()).unwrap();
        assert_eq!(
            preview(&config, &body.to_string()).unwrap()["before"],
            after
        );
        // What Moli would refuse: said, nothing written.
        let bad = json!({ "section": { "meter": [{ "id": "x", "name": "X", "point": "x", "role": "appliance" }] } });
        let seen = preview(&config, &bad.to_string()).unwrap();
        assert!(
            seen["error"].as_str().unwrap().contains("not a point id"),
            "{seen}"
        );
        assert_eq!(seen["after"], "");
    }

    #[test]
    fn the_official_engine_runs_the_hours_and_the_tariff_that_follows_them() {
        use std::io::{Read as _, Write as _};
        let port = std::net::TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let (dir, config) = house("engine", "");
        std::fs::write(
            &config,
            format!(
                "[server]\nlisten = \"127.0.0.1:{port}\"\ndata_dir = {}\n",
                toml::Value::String(dir.0.display().to_string())
            ),
        )
        .unwrap();
        let section = json!({ "tariff": { "prices": { "HP": 0.2081, "HC": 0.1635 } } });
        put(
            &config,
            &json!({ "section": section, "offpeak": ["22:00-06:00"] }).to_string(),
        )
        .unwrap();
        let moli = moli_os::hosted::Hosted::start(
            &config,
            moli_runtime::MasterKey::generate().unwrap(),
            "482913".into(),
        )
        .unwrap();
        let ask = |path: &str| -> Option<String> {
            let mut socket = std::net::TcpStream::connect(("127.0.0.1", port)).ok()?;
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .ok()?;
            write!(
                socket,
                "GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n"
            )
            .ok()?;
            let mut response = String::new();
            socket.read_to_string(&mut response).ok()?;
            Some(response).filter(|r| r.starts_with("HTTP/1.1 200"))
        };
        let end = std::time::Instant::now() + std::time::Duration::from_secs(20);
        let mut seen = None;
        while std::time::Instant::now() < end {
            seen = ask("/api/health").filter(|h| h.contains(r#""instance":"tarif""#));
            if seen.is_some() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        moli.stop().unwrap();
        assert!(seen.is_some(), "Moli never ran the off-peak hours");
    }

    #[test]
    fn the_points_say_what_they_hold_and_what_measures_is_listed() {
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
        // Every device that measures, its power first; the Linky's VA is no
        // consumption to add up.
        let measuring = out["measuring"].as_array().unwrap();
        assert_eq!(measuring.len(), 2, "{measuring:?}");
        assert_eq!(measuring[0]["point"], "meross-1:aaaa/power");
        assert_eq!(measuring[0]["guess"], "appliance");
        assert_eq!(measuring[0]["followed"], false);
        assert_eq!(measuring[1]["followed"], true);
    }

    #[test]
    fn what_a_device_is_is_guessed_from_what_it_says() {
        assert_eq!(guess("linky-tic:compteur", &json!({})), "grid");
        assert_eq!(guess("shelly:abc", &json!({ "model": "Pro 3EM" })), "total");
        assert_eq!(guess("solarman:x", &json!({})), "solar");
        assert_eq!(
            guess("meross-1:aaaa", &json!({ "model": "MSS310" })),
            "appliance"
        );
    }
}
