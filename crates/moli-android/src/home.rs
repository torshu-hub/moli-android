//! The « Pièces et affichage » screen of the app (`HomeActivity.java`): the
//! house's layout, `data/home.json` (docs/components/dashboard.md, « La mise
//! en page de la maison »), which no official route writes. The screen sends
//! whole top-level keys; the others stay as they are, unknown ones included.
//! Written whole then swapped in, as the assistant's own edits do
//! (`moli-assistant` `layout_edit.rs`): the dashboard reads it again at each
//! `GET /api/home`, so Moli is not restarted. A file that is not a JSON
//! object is never overwritten.

use std::path::{Path, PathBuf};

use anyhow::{Context as _, anyhow, bail};
use moli_net::Method;
use serde::Deserialize;
use serde_json::{Map, Value as Json, json};

use crate::energy::Session;
use crate::plugs::Moli;
use crate::setup;

fn path(config: &Path) -> anyhow::Result<PathBuf> {
    Ok(setup::data_dir(config)?.join("home.json"))
}

/// The layout as written (`{}` when there is none yet).
fn layout(path: &Path) -> anyhow::Result<Map<String, Json>> {
    let bytes = match std::fs::read(path) {
        Ok(bytes) => bytes,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Map::new()),
        Err(e) => return Err(e).with_context(|| format!("cannot read {}", path.display())),
    };
    match serde_json::from_slice(&bytes) {
        Ok(Json::Object(layout)) => Ok(layout),
        Ok(_) => bail!("home.json is not an object: left as it is"),
        Err(e) => bail!("home.json is not valid JSON ({e}): left as it is"),
    }
}

/// `{"layout": {…}}`: what `home.json` holds.
pub fn read(config: &Path) -> anyhow::Result<Json> {
    Ok(json!({ "layout": layout(&path(config)?)? }))
}

/// What the screen writes: whole top-level keys, `null` to take one out.
#[derive(Debug, Deserialize)]
struct Put {
    set: Map<String, Json>,
}

/// The layout with `body` applied, held to the shapes the dashboard reads.
fn changed(mut layout: Map<String, Json>, body: &str) -> anyhow::Result<Map<String, Json>> {
    let Put { set } = serde_json::from_str(body).context("invalid change")?;
    for (key, value) in set {
        if value.is_null() {
            layout.remove(&key);
        } else {
            check(&key, &value)?;
            layout.insert(key, value);
        }
    }
    Ok(layout)
}

fn text(value: &Json) -> Option<&str> {
    value.as_str().map(str::trim).filter(|t| !t.is_empty())
}

/// A room's name as the dashboard compares them: no accents, no case, single spaces.
fn norm(name: &str) -> String {
    let folded: String = name
        .chars()
        .map(|c| match c {
            'à' | 'â' | 'ä' | 'á' | 'À' | 'Â' | 'Ä' | 'Á' => 'a',
            'é' | 'è' | 'ê' | 'ë' | 'É' | 'È' | 'Ê' | 'Ë' => 'e',
            'î' | 'ï' | 'í' | 'Î' | 'Ï' | 'Í' => 'i',
            'ô' | 'ö' | 'ó' | 'Ô' | 'Ö' | 'Ó' => 'o',
            'ù' | 'û' | 'ü' | 'ú' | 'Ù' | 'Û' | 'Ü' | 'Ú' => 'u',
            'ç' | 'Ç' => 'c',
            other => other,
        })
        .collect();
    folded
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

/// The keys this screen writes, in the shapes the dashboard reads; any other
/// key is the family's and goes through untouched.
fn check(key: &str, value: &Json) -> anyhow::Result<()> {
    let list_of_ids = |what: &str| -> anyhow::Result<()> {
        let list = value
            .as_array()
            .ok_or_else(|| anyhow!("{what}: not a list"))?;
        if list.iter().any(|v| text(v).is_none()) {
            bail!("{what}: an entry is not a device");
        }
        Ok(())
    };
    match key {
        "title" | "theme" => {
            text(value).ok_or_else(|| anyhow!("{key}: empty"))?;
        }
        "rooms" => {
            let rooms = value
                .as_array()
                .ok_or_else(|| anyhow!("rooms: not a list"))?;
            let mut seen = std::collections::HashSet::new();
            for room in rooms {
                let name =
                    text(&room["name"]).ok_or_else(|| anyhow!("rooms: a room has no name"))?;
                let aliases = room["aliases"].as_array().map_or(&[][..], Vec::as_slice);
                for one in std::iter::once(name).chain(aliases.iter().filter_map(text)) {
                    if !seen.insert(norm(one)) {
                        bail!("rooms: « {one} » names two rooms");
                    }
                }
                if !room["outside"].is_null() && !room["outside"].is_boolean() {
                    bail!("rooms: « {name} »: outside is true or false");
                }
            }
        }
        "hidden" | "quiet" => list_of_ids(key)?,
        "doors" => {
            let doors = value
                .as_array()
                .ok_or_else(|| anyhow!("doors: not a list"))?;
            if doors.iter().any(|d| text(&d["id"]).is_none()) {
                bail!("doors: an entry has no device");
            }
        }
        "favorites" => {
            let favorites = value
                .as_object()
                .ok_or_else(|| anyhow!("favorites: not an object"))?;
            for (list, entries) in favorites {
                let id = if list == "switches" { "point" } else { "id" };
                let entries = entries
                    .as_array()
                    .ok_or_else(|| anyhow!("favorites.{list}: not a list"))?;
                if entries.iter().any(|e| text(&e[id]).is_none()) {
                    bail!("favorites.{list}: an entry has no {id}");
                }
                if list == "switches"
                    && entries
                        .iter()
                        .any(|e| !e[id].as_str().unwrap_or_default().contains('/'))
                {
                    bail!("favorites.switches: a point is « device/key »");
                }
            }
        }
        "menu" => {
            value
                .as_object()
                .ok_or_else(|| anyhow!("menu: not an object"))?;
        }
        _ => {}
    }
    Ok(())
}

/// The keys `body` sets, now and once written, as the file says them.
fn excerpt(layout: &Map<String, Json>, keys: &[String]) -> anyhow::Result<String> {
    let part: Map<String, Json> = keys
        .iter()
        .filter_map(|k| layout.get(k).map(|v| (k.clone(), v.clone())))
        .collect();
    Ok(if part.is_empty() {
        String::new()
    } else {
        serde_json::to_string_pretty(&part)?
    })
}

/// « Voir ce qui sera écrit »: `{before, after, error}`, the keys the screen
/// changes, now and once written. Writes nothing.
pub fn preview(config: &Path, body: &str) -> anyhow::Result<Json> {
    let now = layout(&path(config)?)?;
    let keys: Vec<String> = serde_json::from_str::<Put>(body)
        .context("invalid change")?
        .set
        .keys()
        .cloned()
        .collect();
    let before = excerpt(&now, &keys)?;
    Ok(match changed(now, body) {
        Ok(after) => json!({ "before": before, "after": excerpt(&after, &keys)?, "error": null }),
        Err(e) => json!({ "before": before, "after": "", "error": format!("{e:#}") }),
    })
}

/// Writes the keys `body` sets, the others kept: whole, then swapped in.
pub fn put(config: &Path, body: &str) -> anyhow::Result<()> {
    let path = path(config)?;
    let after = changed(layout(&path)?, body)?;
    let temp = path.with_extension("json.tmp");
    {
        use std::io::Write as _;
        let mut file = std::fs::File::create(&temp)
            .with_context(|| format!("cannot write {}", temp.display()))?;
        file.write_all(&serde_json::to_vec_pretty(&Json::Object(after))?)?;
        // On disk before it replaces the file: a power cut leaves the old one or the new one.
        file.sync_all()?;
    }
    std::fs::rename(&temp, &path).with_context(|| format!("cannot replace {}", path.display()))
}

/// What a device is, for the screen's lists: `camera`, `climate`, `light`,
/// `switch` (something that turns on and off), `door`, else `sensor`.
fn kind(device: &Json) -> &'static str {
    let points = device["points"].as_array().map_or(&[][..], Vec::as_slice);
    let has_key = |keys: &[&str]| {
        points
            .iter()
            .any(|p| keys.contains(&p["key"].as_str().unwrap_or_default()))
    };
    let semantic = |s: &str| points.iter().any(|p| p["semantic"].as_str() == Some(s));
    let switchable = points.iter().any(|p| {
        p["semantic"].as_str() == Some("on_off") && p["access"]["write"].as_bool() == Some(true)
    });
    let id = device["id"].as_str().unwrap_or_default();
    if device["camera"].as_bool() == Some(true) {
        "camera"
    } else if has_key(&[
        "target_temperature",
        "occupied_heating_setpoint",
        "heat_setpoint",
        "current_heating_setpoint",
    ]) {
        "climate"
    } else if switchable
        && (id.starts_with("hue:")
            || id.starts_with("lumieres:")
            || semantic("brightness")
            || semantic("color_temp")
            || semantic("color"))
    {
        "light"
    } else if switchable {
        "switch"
    } else if semantic("contact") {
        "door"
    } else {
        "sensor"
    }
}

/// The house as Moli runs it, for the screens' lists: `{"devices": [{id,
/// name, room, kind, online, switches: [{point, label}], points: [{point,
/// label, semantic, type, unit, writable, value}]}], "rooms": [{name,
/// outside, added}]}` (the rooms as `/api/home` gives them: the outside
/// decided, the rooms only the devices or the plan name added). Moli must run.
pub fn house(request: &Session) -> anyhow::Result<Json> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    let (snapshot, home) = runtime.block_on(async {
        let moli = Moli::open(request.port, &request.pin).await?;
        let devices = moli.call(Method::GET, "/api/devices", &Json::Null).await;
        let home = moli.call(Method::GET, "/api/home", &Json::Null).await;
        moli.close().await;
        let (status, snapshot) = devices?;
        anyhow::ensure!(status == 200, "Moli: HTTP {status}");
        Ok((
            snapshot,
            home.ok().filter(|(s, _)| *s == 200).map(|(_, j)| j),
        ))
    })?;
    Ok(house_of(&snapshot, home.as_ref()))
}

fn house_of(snapshot: &Json, home: Option<&Json>) -> Json {
    let mut devices = Vec::new();
    for device in snapshot["devices"].as_array().into_iter().flatten() {
        let Some(id) = device["id"].as_str() else {
            continue;
        };
        let name = text(&device["label"]["name"])
            .or_else(|| text(&device["native_name"]))
            .unwrap_or(id);
        let room = text(&device["label"]["room"]).or_else(|| text(&device["native_room"]));
        let switches: Vec<Json> = device["points"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|p| {
                p["semantic"].as_str() == Some("on_off")
                    && p["access"]["write"].as_bool() == Some(true)
            })
            .filter_map(|p| {
                let key = p["key"].as_str()?;
                let label = text(&device["label"]["points"][key])
                    .or_else(|| text(&p["label"]))
                    .unwrap_or(key);
                Some(json!({ "point": format!("{id}/{key}"), "label": label }))
            })
            .collect();
        let points: Vec<Json> = device["points"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|p| {
                let key = p["key"].as_str()?;
                Some(json!({
                    "point": format!("{id}/{key}"),
                    "label": text(&device["label"]["points"][key]).or_else(|| text(&p["label"])).unwrap_or(key),
                    "semantic": p["semantic"],
                    "type": p["kind"]["type"],
                    "kind": p["kind"],
                    "unit": p["unit"],
                    "writable": p["access"]["write"].as_bool() == Some(true),
                    "value": device["state"][key]["value"],
                }))
            })
            .collect();
        devices.push(json!({
            "id": id,
            "name": name,
            "room": room,
            "kind": kind(device),
            "online": device["online"],
            "switches": switches,
            "points": points,
        }));
    }
    let rooms: Vec<Json> = home
        .and_then(|h| h["rooms"].as_array())
        .into_iter()
        .flatten()
        .filter_map(|r| {
            Some(json!({
                "name": text(&r["name"])?,
                "outside": r["outside"].as_bool().unwrap_or(false),
                "added": r["added"].as_bool().unwrap_or(false),
            }))
        })
        .collect();
    json!({ "devices": devices, "rooms": rooms })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Dir(PathBuf);
    impl Drop for Dir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    /// A house whose data sit next to its moli.toml, with `home` as its home.json.
    fn house(name: &str, home: Option<&str>) -> (Dir, PathBuf) {
        let dir = std::env::temp_dir().join(format!("moli-home-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let config = dir.join("moli.toml");
        std::fs::write(
            &config,
            format!("[server]\ndata_dir = {:?}\n", dir.display().to_string()),
        )
        .unwrap();
        if let Some(home) = home {
            std::fs::write(dir.join("home.json"), home).unwrap();
        }
        (Dir(dir), config)
    }

    fn written(config: &Path) -> Json {
        serde_json::from_slice(&std::fs::read(path(config).unwrap()).unwrap()).unwrap()
    }

    #[test]
    fn the_keys_set_are_written_and_the_others_kept() {
        let (_d, config) = house(
            "keep",
            Some(
                r#"{"title":"Maison","infra":{"box":{"name":"Freebox"}},"hidden":["a:1"],"rooms":[{"name":"Salon"}]}"#,
            ),
        );
        put(
            &config,
            r#"{"set":{"rooms":[{"name":"Salon","icon":"sofa-outline","floor":"en bas"},{"name":"Bureau","aliases":["Office"],"outside":false}],"hidden":null}}"#,
        )
        .unwrap();
        let now = written(&config);
        assert_eq!(now["rooms"][0]["icon"], "sofa-outline");
        assert_eq!(now["rooms"][1]["aliases"][0], "Office");
        assert!(now.get("hidden").is_none(), "null takes a key out");
        // Untouched: the family's keys, unknown ones included.
        assert_eq!(now["title"], "Maison");
        assert_eq!(now["infra"]["box"]["name"], "Freebox");
        assert!(!path(&config).unwrap().with_extension("json.tmp").exists());
    }

    #[test]
    fn a_new_house_gets_its_file() {
        let (_d, config) = house("new", None);
        assert_eq!(read(&config).unwrap()["layout"], json!({}));
        put(&config, r#"{"set":{"title":"Chez nous"}}"#).unwrap();
        assert_eq!(written(&config)["title"], "Chez nous");
    }

    #[test]
    fn what_the_dashboard_could_not_read_is_never_written() {
        let (_d, config) = house("refused", Some(r#"{"rooms":[{"name":"Salon"}]}"#));
        let refused = |body: &str| put(&config, body).unwrap_err().to_string();
        assert!(
            refused(r#"{"set":{"rooms":[{"name":"Salon"},{"name":"salon"}]}}"#)
                .contains("two rooms")
        );
        assert!(
            refused(
                r#"{"set":{"rooms":[{"name":"Salon"},{"name":"Séjour","aliases":["SALON"]}]}}"#
            )
            .contains("two rooms")
        );
        assert!(refused(r#"{"set":{"rooms":[{"name":" "}]}}"#).contains("no name"));
        assert!(refused(r#"{"set":{"hidden":["a:1",""]}}"#).contains("not a device"));
        assert!(
            refused(r#"{"set":{"favorites":{"switches":[{"point":"tuya:x"}]}}}"#)
                .contains("device/key")
        );
        assert!(refused(r#"{"set":{"doors":[{"name":"Entrée"}]}}"#).contains("no device"));
        assert_eq!(written(&config), json!({"rooms":[{"name":"Salon"}]}));
    }

    #[test]
    fn a_file_that_is_not_an_object_is_left_alone() {
        let (_d, config) = house("broken", Some("{ not json"));
        assert!(read(&config).is_err());
        assert!(put(&config, r#"{"set":{"title":"X"}}"#).is_err());
        assert_eq!(
            std::fs::read_to_string(path(&config).unwrap()).unwrap(),
            "{ not json"
        );
    }

    #[test]
    fn the_preview_shows_the_keys_changed_and_writes_nothing() {
        let (_d, config) = house("preview", Some(r#"{"title":"Maison","hidden":["a:1"]}"#));
        let seen = preview(&config, r#"{"set":{"hidden":["a:1","b:2"]}}"#).unwrap();
        assert!(seen["before"].as_str().unwrap().contains("a:1"));
        assert!(
            !seen["before"].as_str().unwrap().contains("title"),
            "only what changes"
        );
        assert!(seen["after"].as_str().unwrap().contains("b:2"));
        assert!(seen["error"].is_null());
        let refused = preview(&config, r#"{"set":{"rooms":[{"name":""}]}}"#).unwrap();
        assert!(refused["error"].as_str().unwrap().contains("no name"));
        assert_eq!(written(&config), json!({"title":"Maison","hidden":["a:1"]}));
    }

    #[test]
    fn devices_say_what_they_are_where_they_are_and_what_they_switch() {
        let on_off = |key: &str| json!({"key": key, "label": key, "semantic": "on_off", "access": {"write": true}});
        let snapshot = json!({"devices": [
            {"id": "hue:1", "native_name": "Plafonnier", "label": {"room": "Salon"}, "points": [on_off("on"), {"key": "brightness", "semantic": "brightness", "access": {"write": true}}]},
            {"id": "tuya:m", "native_name": "Multiprise", "native_room": "Bureau", "label": {"name": "Écran", "points": {"switch_2": "Lampe"}}, "points": [on_off("switch_1"), on_off("switch_2")]},
            {"id": "z2m:p", "native_name": "Porte", "label": {}, "points": [{"key": "contact", "semantic": "contact", "access": {"write": false}}]},
            {"id": "z2m:v", "native_name": "Vanne", "label": {}, "points": [{"key": "occupied_heating_setpoint", "semantic": "temperature", "access": {"write": true}}]},
            {"id": "reolink:c", "native_name": "Allée", "label": {}, "camera": true, "points": []},
            {"id": "z2m:t", "native_name": "Sonde", "label": {}, "points": [{"key": "temperature", "semantic": "temperature", "access": {"write": false}}]}
        ]});
        let home = json!({"rooms": [{"name": "Salon", "outside": false}, {"name": "Jardin", "outside": true, "added": true}]});
        let seen = house_of(&snapshot, Some(&home));
        let kinds: Vec<&str> = seen["devices"]
            .as_array()
            .unwrap()
            .iter()
            .map(|d| d["kind"].as_str().unwrap())
            .collect();
        assert_eq!(
            kinds,
            ["light", "switch", "door", "climate", "camera", "sensor"]
        );
        let strip = &seen["devices"][1];
        assert_eq!(
            (strip["name"].as_str(), strip["room"].as_str()),
            (Some("Écran"), Some("Bureau"))
        );
        assert_eq!(
            strip["switches"][1],
            json!({"point": "tuya:m/switch_2", "label": "Lampe"})
        );
        assert_eq!(
            seen["rooms"][1],
            json!({"name": "Jardin", "outside": true, "added": true})
        );
    }
}
