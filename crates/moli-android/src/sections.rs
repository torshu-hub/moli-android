//! The settings screens of the app that write whole sections of `moli.toml`
//! (`[[heating]]`, `[heating_house]`, `[[fixture]]`, `[[scene]]`, `[guard]`…):
//! read as JSON, replaced whole, the other sections and their comments kept.
//! Nothing is written that Moli's own checks refuse: the file as it would
//! be goes through `moli_os::hosted::check` first; then the screen restarts
//! Moli, the previous file back if it still refuses (`setup::rollback`).

use std::path::Path;

use anyhow::{Context as _, anyhow, bail};
use serde::Deserialize;
use serde_json::{Map, Value as Json, json};
use toml_edit::{ArrayOfTables, DocumentMut, Item, Table};

use crate::setup;

/// The sections the screens may write (top-level keys of `moli.toml`).
const WRITABLE: &[&str] = &[
    "heating",
    "heating_house",
    "fixture",
    "scene",
    "rhythm",
    "outdoor",
    "sun",
    "guard",
    "mcp",
    "assistant",
    "server",
    "automations",
];

/// `{"sections": {name: value or null}}`: the sections asked for, as written.
pub fn read(config: &Path, names: &[String]) -> anyhow::Result<Json> {
    let doc = setup::read(config)?;
    let all: toml::Table = toml::from_str(&doc.to_string())?;
    let mut out = Map::new();
    for name in names {
        let value = all
            .get(name)
            .map(serde_json::to_value)
            .transpose()?
            .unwrap_or(Json::Null);
        out.insert(name.clone(), value);
    }
    Ok(json!({ "sections": out }))
}

/// What a screen writes: whole sections, `null` to take one out.
#[derive(Debug, Deserialize)]
struct Put {
    set: Map<String, Json>,
}

/// The file with `body` applied (not checked yet), and the sections it sets.
fn changed(doc: &DocumentMut, body: &str) -> anyhow::Result<(DocumentMut, Vec<String>)> {
    let Put { set } = serde_json::from_str(body).context("invalid change")?;
    let mut doc = doc.clone();
    let mut names = Vec::new();
    for (name, value) in set {
        if !WRITABLE.contains(&name.as_str()) {
            bail!("[{name}] is not written by this screen");
        }
        let decor = doc
            .get(&name)
            .and_then(Item::as_table)
            .map(|t| t.decor().clone());
        let item = match &value {
            Json::Null => None,
            Json::Object(map) if map.is_empty() => None,
            Json::Array(list) if list.is_empty() => None,
            _ => Some(item(&name, &value)?),
        };
        match item {
            None => {
                doc.remove(&name);
            }
            Some(mut item) => {
                if let (Some(decor), Some(table)) = (decor, item.as_table_mut()) {
                    *table.decor_mut() = decor;
                }
                if doc.contains_key(&name) {
                    doc[name.as_str()] = item;
                } else {
                    doc.insert(&name, item);
                }
            }
        }
        names.push(name);
    }
    Ok((doc, names))
}

/// The file as it would be, held to Moli's own checks (written beside it,
/// read by the engine, taken away).
fn checked(config: &Path, doc: &DocumentMut) -> anyhow::Result<()> {
    let trial = config.with_file_name("moli.toml.essai");
    std::fs::write(&trial, doc.to_string()).context("cannot write the trial file")?;
    let verdict = moli_os::hosted::check(&trial);
    let _ = std::fs::remove_file(&trial);
    verdict
}

/// Writes the sections of `body` once Moli's checks accept the file.
pub fn put(config: &Path, body: &str) -> anyhow::Result<()> {
    let (doc, _) = changed(&setup::read(config)?, body)?;
    checked(config, &doc)?;
    setup::write(config, &doc)
}

/// « Voir ce qui sera écrit »: `{before, after, error}`, the sections the
/// screen changes, now and once written. Writes nothing in `moli.toml`.
pub fn preview(config: &Path, body: &str) -> anyhow::Result<Json> {
    let doc = setup::read(config)?;
    let (after, names) = changed(&doc, body)?;
    let error = checked(config, &after).err().map(|e| format!("{e:#}"));
    Ok(json!({
        "before": excerpt(&doc, &names),
        "after": if error.is_some() { String::new() } else { excerpt(&after, &names) },
        "error": error,
    }))
}

fn excerpt(doc: &DocumentMut, names: &[String]) -> String {
    let mut part = DocumentMut::new();
    for name in names {
        if let Some(item) = doc.get(name) {
            let mut item = item.clone();
            if let Some(t) = item.as_table_mut() {
                t.decor_mut().clear();
            }
            part.insert(name, item);
        }
    }
    part.to_string().trim().to_owned()
}

/// The keys in the order a person reads them; the others after, by name.
const ORDER: &[&str] = &[
    "id",
    "name",
    "room",
    "sensor",
    "valves",
    "bulbs",
    "power",
    "regulation",
    "force_offset",
    "correction",
    "hysteresis",
    "min",
    "max",
    "initial",
    "max_boost",
    "boost_default_minutes",
    "hold_default_minutes",
    "programmes",
    "away",
    "away_delay",
    "away_mode",
    "cycle",
    "lights",
    "orders",
    "curve",
    "forecast",
    "forecast_stale_after",
    "station",
    "device",
    "stale_after",
    "enter",
    "exit",
    "facade",
    "azimuth",
    "min_elevation",
    "dashboard",
    "protected_rooms",
    "protected_devices",
    "protect_unassigned",
    "quiet_hours",
    "from",
    "to",
    "rooms",
    "code_each_time",
    "agents_may",
    "point",
    "values",
    "disabled_tools",
];

fn rank(key: &str) -> (usize, &str) {
    (
        ORDER.iter().position(|o| *o == key).unwrap_or(ORDER.len()),
        key,
    )
}

/// A section as `moli.toml` writes it: `[name]` with its own `[[name.sub]]`
/// lists, or `[[name]]` blocks; deeper tables inline.
fn item(name: &str, value: &Json) -> anyhow::Result<Item> {
    let value: toml::Value = toml::Value::try_from(value).map_err(|e| anyhow!("[{name}] {e}"))?;
    Ok(match value {
        toml::Value::Table(map) => Item::Table(table(&map, true)?),
        toml::Value::Array(list) if list.iter().all(toml::Value::is_table) => {
            let mut blocks = ArrayOfTables::new();
            for block in list.iter().filter_map(toml::Value::as_table) {
                blocks.push(table(block, false)?);
            }
            Item::ArrayOfTables(blocks)
        }
        other => Item::Value(
            other
                .to_string()
                .parse()
                .with_context(|| format!("[{name}]"))?,
        ),
    })
}

fn table(map: &toml::Table, top: bool) -> anyhow::Result<Table> {
    let mut keys: Vec<&String> = map.keys().collect();
    keys.sort_by_key(|k| rank(k));
    let mut out = Table::new();
    for key in keys {
        let item = match &map[key] {
            toml::Value::Table(inner) if top => Item::Table(table(inner, false)?),
            toml::Value::Array(list)
                if top && !list.is_empty() && list.iter().all(toml::Value::is_table) =>
            {
                let mut blocks = ArrayOfTables::new();
                for block in list.iter().filter_map(toml::Value::as_table) {
                    blocks.push(table(block, false)?);
                }
                Item::ArrayOfTables(blocks)
            }
            toml::Value::Table(inner) => {
                Item::Value(inline(inner).parse().with_context(|| key.clone())?)
            }
            other => Item::Value(other.to_string().parse().with_context(|| key.clone())?),
        };
        out.insert(key, item);
    }
    Ok(out)
}

/// A table inline, its keys in reading order.
fn inline(map: &toml::Table) -> String {
    let mut keys: Vec<&String> = map.keys().collect();
    keys.sort_by_key(|k| rank(k));
    let parts: Vec<String> = keys
        .iter()
        .map(|k| {
            let value = match &map[*k] {
                toml::Value::Table(inner) => inline(inner),
                other => other.to_string(),
            };
            format!("{} = {value}", toml_edit::Key::new(k.as_str()))
        })
        .collect();
    format!("{{ {} }}", parts.join(", "))
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
        let dir = std::env::temp_dir().join(format!("moli-sections-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let config = dir.join("moli.toml");
        std::fs::write(&config, text).unwrap();
        (Dir(dir), config)
    }

    const START: &str = "# La maison.\n[server]\ntimezone = \"Europe/Paris\"\n\n# Zigbee.\n[[driver]]\nid = \"z2m\"\nkind = \"z2m\"\n\n[driver.options]\nhost = \"127.0.0.1\"\n";

    const HEATING: &str = r#"{"set":{
        "heating":[{"id":"bureau","name":"Bureau","sensor":"z2m:s/temperature","valves":["z2m:v/occupied_heating_setpoint"],"regulation":"force","force_offset":4.0}],
        "heating_house":{"programmes":["Normal","Vacances"],"away_mode":"Vacances","away_delay":30}
    }}"#;

    #[test]
    fn sections_are_written_whole_and_the_rest_kept() {
        let (_d, config) = house("write", START);
        put(&config, HEATING).unwrap();
        let text = std::fs::read_to_string(&config).unwrap();
        assert!(text.starts_with("# La maison.\n[server]"), "{text}");
        assert!(text.contains("# Zigbee.\n[[driver]]"), "{text}");
        assert!(
            text.contains("[[heating]]\nid = \"bureau\"\nname = \"Bureau\"\nsensor"),
            "{text}"
        );
        assert!(
            text.contains("[heating_house]\nprogrammes = [\"Normal\", \"Vacances\"]"),
            "{text}"
        );
        let read = read(
            &config,
            &["heating".into(), "heating_house".into(), "sun".into()],
        )
        .unwrap();
        assert_eq!(
            read["sections"]["heating"][0]["valves"][0],
            "z2m:v/occupied_heating_setpoint"
        );
        assert_eq!(read["sections"]["heating_house"]["away_delay"], 30);
        assert!(read["sections"]["sun"].is_null());
        // Taken out: null, or an empty list.
        put(&config, r#"{"set":{"heating_house":null,"heating":[]}}"#).unwrap();
        let text = std::fs::read_to_string(&config).unwrap();
        assert!(!text.contains("heating"), "{text}");
        assert!(text.contains("[[driver]]"));
    }

    #[test]
    fn what_moli_refuses_is_never_written() {
        let (_d, config) = house("refused", START);
        // A programme of absence that is not one of the house's.
        let wrong = HEATING.replace("\"away_mode\":\"Vacances\"", "\"away_mode\":\"Ailleurs\"");
        assert!(put(&config, &wrong).is_err());
        let unknown = r#"{"set":{"heating":[{"id":"bureau","name":"Bureau","sensor":"z2m:s/temperature","valves":[],"colour":"red"}]}}"#;
        assert!(put(&config, unknown).is_err());
        assert!(
            put(&config, r#"{"set":{"driver":[]}}"#)
                .unwrap_err()
                .to_string()
                .contains("not written")
        );
        assert_eq!(std::fs::read_to_string(&config).unwrap(), START);
        assert!(!config.with_file_name("moli.toml.essai").exists());
    }

    #[test]
    fn the_preview_shows_before_and_after_and_writes_nothing() {
        let (_d, config) = house("preview", START);
        let seen = preview(&config, HEATING).unwrap();
        assert_eq!(seen["before"], "");
        assert!(seen["after"].as_str().unwrap().contains("[[heating]]"));
        assert!(seen["error"].is_null());
        let wrong = HEATING.replace("\"away_mode\":\"Vacances\"", "\"away_mode\":\"Ailleurs\"");
        let seen = preview(&config, &wrong).unwrap();
        assert!(
            seen["error"].as_str().unwrap().contains("Ailleurs"),
            "{seen}"
        );
        assert_eq!(std::fs::read_to_string(&config).unwrap(), START);
    }

    #[test]
    fn nested_lists_become_their_own_blocks() {
        let (_d, config) = house("nested", START);
        put(
            &config,
            r#"{"set":{"sun":{"exit":75,"facade":[{"id":"sud","name":"Sud","azimuth":180}]}}}"#,
        )
        .unwrap();
        let text = std::fs::read_to_string(&config).unwrap();
        assert!(
            text.contains(
                "[sun]\nexit = 75\n\n[[sun.facade]]\nid = \"sud\"\nname = \"Sud\"\nazimuth = 180"
            ),
            "{text}"
        );
    }
}
