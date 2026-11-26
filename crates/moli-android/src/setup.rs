//! The integrations screen of the app (`Setup.java`): the official catalogue,
//! the official `[[driver]]` blocks of `moli.toml`, the official vault. What
//! the engine checks, it checks itself when it starts again: the screen puts
//! the previous `moli.toml` back if it refuses the new one.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use anyhow::{Context as _, bail};
use moli_runtime::MasterKey;
use serde::Deserialize;
use serde_json::{Value as Json, json};
use toml_edit::{ArrayOfTables, DocumentMut, InlineTable, Item, Table, Value};

/// `(id, integration.toml, onboarding.md)` of each package (`build.rs`).
static FILES: &[(&str, &str, &str)] = include!(concat!(env!("OUT_DIR"), "/catalogue.rs"));

/// The previous `moli.toml`, kept until the engine accepted the new one.
const PREVIOUS: &str = "moli.toml.avant";

#[derive(Deserialize)]
struct Manifest {
    integration: Integration,
    #[serde(default)]
    needs: Needs,
}

#[derive(Deserialize)]
struct Integration {
    name: String,
    #[serde(default)]
    brands: Vec<String>,
    #[serde(default)]
    models: Vec<String>,
    transport: String,
    driver: String,
}

#[derive(Default, Deserialize)]
struct Needs {
    #[serde(default)]
    secrets: Vec<String>,
    #[serde(default)]
    vars: Vec<String>,
    #[serde(default)]
    options: Vec<String>,
}

struct Entry {
    id: &'static str,
    manifest: Manifest,
    onboarding: &'static str,
}

impl Entry {
    fn profile(&self) -> bool {
        self.manifest.integration.driver == "profile"
    }

    /// The `kind` of its `[[driver]]` block.
    fn kind(&self) -> &str {
        &self.manifest.integration.driver
    }
}

fn entries() -> &'static [Entry] {
    static ENTRIES: OnceLock<Vec<Entry>> = OnceLock::new();
    ENTRIES.get_or_init(|| {
        FILES
            .iter()
            // `moli-os catalogue check` holds every manifest to its schema.
            .filter_map(|(id, manifest, onboarding)| {
                Some(Entry {
                    id,
                    manifest: toml::from_str(manifest).ok()?,
                    onboarding,
                })
            })
            .collect()
    })
}

fn entry(id: &str) -> Option<&'static Entry> {
    entries().iter().find(|e| e.id == id)
}

/// The package a block runs: its profile, else the package of its kind.
fn package_of(kind: &str, options: Option<&Table>) -> Option<&'static Entry> {
    if kind == "profile" {
        let profile = options?.get("profile")?.as_str()?;
        entry(profile).filter(|e| e.profile())
    } else {
        entries().iter().find(|e| !e.profile() && e.kind() == kind)
    }
}

/// The catalogue, as the screen lists it (JSON).
pub fn catalogue() -> String {
    let list: Vec<Json> = entries()
        .iter()
        .map(|e| {
            let i = &e.manifest.integration;
            json!({
                "id": e.id,
                "name": i.name,
                "brands": i.brands,
                "models": i.models,
                "transport": i.transport,
                "kind": i.driver,
                "secrets": e.manifest.needs.secrets,
                "vars": e.manifest.needs.vars,
                "options": e.manifest.needs.options,
                "onboarding": e.onboarding,
                // Added by its address from the dashboard (`#/ajouter`), not here.
                "addable": e.profile() && moli_profile::addable(e.id),
            })
        })
        .collect();
    Json::Array(list).to_string()
}

fn read(config: &Path) -> anyhow::Result<DocumentMut> {
    let text = match std::fs::read_to_string(config) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(e).with_context(|| format!("cannot read {}", config.display())),
    };
    text.parse()
        .with_context(|| format!("{} is not valid TOML", config.display()))
}

fn blocks(doc: &DocumentMut) -> Option<&ArrayOfTables> {
    doc.get("driver").and_then(Item::as_array_of_tables)
}

/// A value as typed in a field: its text for a string, its TOML otherwise.
fn shown(item: &Item) -> String {
    match item.as_str() {
        Some(text) => text.to_owned(),
        None => item.to_string().trim().to_owned(),
    }
}

/// The house's `[[driver]]` blocks (JSON): the fields of their package, and
/// the rest of their options as TOML (« réglages avancés »).
pub fn drivers(config: &Path) -> anyhow::Result<String> {
    let doc = read(config)?;
    let mut list = Vec::new();
    for block in blocks(&doc).into_iter().flat_map(ArrayOfTables::iter) {
        let id = block.get("id").and_then(Item::as_str).unwrap_or_default();
        let kind = block.get("kind").and_then(Item::as_str).unwrap_or_default();
        let enabled = block.get("enabled").and_then(Item::as_bool) != Some(false);
        let options = block.get("options").and_then(Item::as_table_like);
        let mut rest = Table::new();
        if let Some(options) = options {
            for (key, item) in options.iter() {
                rest.insert(key, item.clone());
            }
        }
        let package = package_of(kind, block.get("options").and_then(Item::as_table));
        let mut fields = BTreeMap::new();
        let mut vars = BTreeMap::new();
        if let Some(package) = package {
            for name in &package.manifest.needs.options {
                if let Some(item) = rest.remove(name) {
                    fields.insert(name.clone(), shown(&item));
                }
            }
            if package.profile() {
                rest.remove("profile");
                if let Some(table) = rest.get("vars").and_then(Item::as_table_like) {
                    for (key, item) in table.iter() {
                        vars.insert(key.to_owned(), shown(item));
                    }
                }
                rest.remove("vars");
            }
        }
        rest.set_implicit(false);
        list.push(json!({
            "id": id,
            "kind": kind,
            "enabled": enabled,
            "integration": package.map(|p| p.id),
            "fields": fields,
            "vars": vars,
            "extra": rest.to_string().trim(),
        }));
    }
    Ok(Json::Array(list).to_string())
}

/// What the screen sends to add or change one block.
#[derive(Debug, Deserialize)]
pub struct Change {
    /// The block changed (its id), none for a new one.
    #[serde(default)]
    previous: Option<String>,
    id: String,
    integration: String,
    #[serde(default = "yes")]
    enabled: bool,
    #[serde(default)]
    fields: BTreeMap<String, String>,
    #[serde(default)]
    vars: BTreeMap<String, String>,
    /// The other options, as TOML.
    #[serde(default)]
    extra: String,
}

fn yes() -> bool {
    true
}

/// A field's text as a TOML value: `true`/`false`, a whole number, a list or
/// a table typed as TOML; any other text is a string (an address, a name).
fn typed(text: &str) -> Value {
    let text = text.trim();
    let parsed = || format!("v = {text}").parse::<DocumentMut>().ok();
    let looks_typed = matches!(text, "true" | "false")
        || text.parse::<i64>().is_ok()
        || text.starts_with('[')
        || text.starts_with('{');
    if looks_typed
        && let Some(doc) = parsed()
        && let Some(value) = doc.get("v").and_then(Item::as_value)
    {
        let mut value = value.clone();
        value.decor_mut().clear();
        return value;
    }
    Value::from(text)
}

fn block(change: &Change, package: &Entry) -> anyhow::Result<Table> {
    let mut options = Table::new();
    let extra: DocumentMut = change
        .extra
        .parse()
        .context("réglages avancés : TOML invalide")?;
    for (key, item) in extra.iter() {
        options.insert(key, item.clone());
    }
    if package.profile() {
        options.insert("profile", toml_edit::value(package.id));
        let vars: InlineTable = change
            .vars
            .iter()
            .filter(|(_, v)| !v.trim().is_empty())
            .map(|(k, v)| (k.as_str(), Value::from(v.trim())))
            .collect();
        if !vars.is_empty() {
            options.insert("vars", toml_edit::value(vars));
        }
    }
    for (key, text) in &change.fields {
        if !text.trim().is_empty() {
            options.insert(key, toml_edit::value(typed(text)));
        }
    }
    let mut table = Table::new();
    table.insert("id", toml_edit::value(change.id.as_str()));
    table.insert("kind", toml_edit::value(package.kind()));
    if !change.enabled {
        table.insert("enabled", toml_edit::value(false));
    }
    if !options.is_empty() {
        options.set_implicit(false);
        table.insert("options", Item::Table(options));
    }
    Ok(table)
}

fn position(doc: &DocumentMut, id: &str) -> Option<usize> {
    blocks(doc)?
        .iter()
        .position(|b| b.get("id").and_then(Item::as_str) == Some(id))
}

/// Adds or replaces one block (the others, and the comments, stay as they are).
pub fn put(config: &Path, change: &Change) -> anyhow::Result<()> {
    let id = change.id.trim();
    if id.is_empty() || id.contains([':', '/']) {
        bail!("invalid driver id {id:?}");
    }
    let package = entry(&change.integration).context("unknown integration")?;
    let mut doc = read(config)?;
    let at = match &change.previous {
        Some(previous) => Some(position(&doc, previous).context("this block is gone")?),
        None => None,
    };
    if position(&doc, id).is_some_and(|other| Some(other) != at) {
        bail!("driver id {id:?} is taken");
    }
    let new = block(change, package)?;
    if let Some(at) = at {
        let old = doc["driver"]
            .as_array_of_tables_mut()
            .and_then(|a| a.get_mut(at))
            .context("this block is gone")?;
        let decor = old.decor().clone();
        *old = new;
        *old.decor_mut() = decor;
    } else {
        if doc.get("driver").is_none() {
            doc.insert("driver", Item::ArrayOfTables(ArrayOfTables::new()));
        }
        doc["driver"]
            .as_array_of_tables_mut()
            .context("`driver` is not a list of [[driver]] blocks")?
            .push(new);
    }
    write(config, &doc)
}

/// Removes one block.
pub fn remove(config: &Path, id: &str) -> anyhow::Result<()> {
    let mut doc = read(config)?;
    let at = position(&doc, id).context("this block is gone")?;
    let list = doc["driver"]
        .as_array_of_tables_mut()
        .context("`driver` is not a list of [[driver]] blocks")?;
    list.remove(at);
    if list.is_empty() {
        doc.remove("driver");
    }
    write(config, &doc)
}

fn previous(config: &Path) -> PathBuf {
    config.with_file_name(PREVIOUS)
}

/// Writes the new file whole (never half), the previous one kept aside.
fn write(config: &Path, doc: &DocumentMut) -> anyhow::Result<()> {
    if config.exists() {
        std::fs::copy(config, previous(config)).context("cannot keep the previous moli.toml")?;
    }
    let temporary = config.with_file_name("moli.toml.nouveau");
    std::fs::write(&temporary, doc.to_string()).context("cannot write moli.toml")?;
    std::fs::File::open(&temporary)?.sync_all()?;
    std::fs::rename(&temporary, config).context("cannot write moli.toml")?;
    Ok(())
}

/// The engine refused the new file: the previous one back.
pub fn rollback(config: &Path) -> anyhow::Result<()> {
    let kept = previous(config);
    if kept.exists() {
        std::fs::rename(&kept, config).context("cannot put the previous moli.toml back")?;
    }
    Ok(())
}

/// The engine accepted the new file: the previous one is no longer needed.
pub fn settle(config: &Path) {
    let _ = std::fs::remove_file(previous(config));
}

/// The vault, beside the house's data (`[server] data_dir`).
fn vault(config: &Path) -> anyhow::Result<PathBuf> {
    let doc = read(config)?;
    let data = doc
        .get("server")
        .and_then(|s| s.get("data_dir"))
        .and_then(Item::as_str)
        .map_or_else(
            || config.parent().unwrap_or(Path::new(".")).to_path_buf(),
            PathBuf::from,
        );
    Ok(data.join("secrets.enc"))
}

fn runtime() -> anyhow::Result<tokio::runtime::Runtime> {
    Ok(tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?)
}

/// Which of `names` the vault holds for `instance` (never their values).
pub fn stored(
    config: &Path,
    key: &MasterKey,
    instance: &str,
    names: &[String],
) -> anyhow::Result<Vec<String>> {
    let vault = vault(config)?;
    if !vault.exists() {
        return Ok(Vec::new());
    }
    let mut held = Vec::new();
    for name in names {
        if moli_runtime::get_secret(&vault, key, &format!("{instance}/{name}"))
            .context("the vault cannot be read with this key")?
            .is_some()
        {
            held.push(name.clone());
        }
    }
    Ok(held)
}

/// Stores the values given (`moli-os secrets set`); an empty one is left as it is.
pub fn store(
    config: &Path,
    key: &MasterKey,
    instance: &str,
    values: &BTreeMap<String, String>,
) -> anyhow::Result<()> {
    let vault = vault(config)?;
    let runtime = runtime()?;
    for (name, value) in values {
        let value = value.trim();
        if value.is_empty() {
            continue;
        }
        runtime
            .block_on(moli_runtime::set_secret(
                &vault,
                key,
                &format!("{instance}/{name}"),
                value,
            ))
            .with_context(|| format!("cannot store {instance}/{name}"))?;
    }
    Ok(())
}

/// Forgets these secrets of `instance` (`moli-os secrets forget`).
pub fn forget(
    config: &Path,
    key: &MasterKey,
    instance: &str,
    names: &[String],
) -> anyhow::Result<()> {
    let vault = vault(config)?;
    if !vault.exists() {
        return Ok(());
    }
    let runtime = runtime()?;
    for name in names {
        runtime.block_on(moli_runtime::forget_secret(
            &vault,
            key,
            &format!("{instance}/{name}"),
        ))?;
    }
    Ok(())
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
    fn house(name: &str, text: &str) -> (Dir, PathBuf) {
        let dir = std::env::temp_dir().join(format!("moli-setup-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let config = dir.join("moli.toml");
        std::fs::write(
            &config,
            format!(
                "{}{text}",
                format_args!(
                    "# La maison.\n[server]\ndata_dir = {}\n",
                    toml::Value::String(dir.display().to_string())
                )
            ),
        )
        .unwrap();
        (Dir(dir), config)
    }
    fn change(json: &str) -> Change {
        serde_json::from_str(json).unwrap()
    }
    fn list(config: &Path) -> Vec<Json> {
        serde_json::from_str::<Json>(&drivers(config).unwrap())
            .unwrap()
            .as_array()
            .unwrap()
            .clone()
    }

    #[test]
    fn the_catalogue_is_whole_and_says_what_each_package_needs() {
        let all: Json = serde_json::from_str(&catalogue()).unwrap();
        let all = all.as_array().unwrap();
        let on_disk = std::fs::read_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/../../integrations"))
            .unwrap()
            .filter(|e| {
                e.as_ref()
                    .unwrap()
                    .path()
                    .join("integration.toml")
                    .is_file()
            })
            .count();
        assert_eq!(all.len(), on_disk, "a manifest no longer reads");
        let meteo = all.iter().find(|e| e["id"] == "open-meteo").unwrap();
        assert_eq!(meteo["kind"], "profile");
        assert_eq!(meteo["vars"], json!(["lat", "lon"]));
        assert!(meteo["onboarding"].as_str().unwrap().contains("open-meteo"));
        assert_eq!(meteo["addable"], false);
        // Meross plugs are added by their address, in the dashboard.
        let plug = all.iter().find(|e| e["id"] == "meross-mss310").unwrap();
        assert_eq!(plug["addable"], true);
        assert_eq!(plug["secrets"], json!(["key"]));
    }

    #[test]
    fn adds_changes_and_removes_an_official_driver_block() {
        let (_dir, config) = house(
            "blocks",
            "\n# Le Zigbee.\n[[driver]]\nid = \"z2m\"\nkind = \"z2m\"\n",
        );
        put(
            &config,
            &change(
                r#"{"id":"meteo","integration":"open-meteo","vars":{"lat":"48.85","lon":"2.35"}}"#,
            ),
        )
        .unwrap();
        let text = std::fs::read_to_string(&config).unwrap();
        assert!(text.starts_with("# La maison.\n"), "{text}");
        assert!(text.contains("# Le Zigbee."), "{text}");
        assert!(
            text.contains("[[driver]]\nid = \"meteo\"\nkind = \"profile\"\n\n[driver.options]\nprofile = \"open-meteo\"\nvars = { lat = \"48.85\", lon = \"2.35\" }\n"),
            "{text}"
        );
        // What the engine reads: the official schema.
        let parsed: toml::Table = toml::from_str(&text).unwrap();
        assert_eq!(
            parsed["driver"][1]["options"]["vars"]["lon"].as_str(),
            Some("2.35")
        );
        // The previous file is kept until the engine accepts this one.
        assert!(
            !std::fs::read_to_string(previous(&config))
                .unwrap()
                .contains("meteo")
        );

        let shown = list(&config);
        assert_eq!(shown[1]["integration"], "open-meteo");
        assert_eq!(shown[1]["vars"]["lat"], "48.85");
        assert_eq!(shown[1]["extra"], "");
        assert_eq!(shown[0]["integration"], "z2m");

        put(
            &config,
            &change(r#"{"previous":"meteo","id":"meteo","integration":"open-meteo","enabled":false,"vars":{"lat":"45.75","lon":"4.85"}}"#),
        )
        .unwrap();
        let shown = list(&config);
        assert_eq!(shown.len(), 2);
        assert_eq!(shown[1]["enabled"], false);
        assert_eq!(shown[1]["vars"]["lat"], "45.75");

        remove(&config, "meteo").unwrap();
        let text = std::fs::read_to_string(&config).unwrap();
        assert!(!text.contains("meteo"), "{text}");
        assert!(
            text.contains("# Le Zigbee.\n[[driver]]\nid = \"z2m\""),
            "{text}"
        );
        remove(&config, "z2m").unwrap();
        assert!(!std::fs::read_to_string(&config).unwrap().contains("driver"));
    }

    #[test]
    fn fields_are_typed_and_other_options_kept_as_written() {
        let (_dir, config) = house("typed", "");
        put(
            &config,
            &change(r#"{"id":"kasa","integration":"kasa","fields":{"host":"192.168.1.20","port":"9999","poll_seconds":""},"extra":"devices = [\"a\", \"b\"]\nlocal = true"}"#),
        )
        .unwrap();
        let parsed: toml::Table =
            toml::from_str(&std::fs::read_to_string(&config).unwrap()).unwrap();
        let options = &parsed["driver"][0]["options"];
        assert_eq!(options["host"].as_str(), Some("192.168.1.20"));
        assert_eq!(options["port"].as_integer(), Some(9999));
        assert!(options.get("poll_seconds").is_none());
        assert_eq!(options["local"].as_bool(), Some(true));
        let shown = list(&config);
        assert_eq!(shown[0]["fields"]["port"], "9999");
        assert_eq!(shown[0]["extra"], "devices = [\"a\", \"b\"]\nlocal = true");
        assert!(
            put(
                &config,
                &change(r#"{"id":"x","integration":"kasa","extra":"= nope"}"#)
            )
            .is_err()
        );
    }

    #[test]
    fn an_id_is_never_taken_twice_nor_malformed() {
        let (_dir, config) = house("ids", "");
        let meteo = r#"{"id":"meteo","integration":"open-meteo","vars":{"lat":"1","lon":"2"}}"#;
        put(&config, &change(meteo)).unwrap();
        assert!(put(&config, &change(meteo)).is_err());
        assert!(
            put(
                &config,
                &change(r#"{"id":"a/b","integration":"open-meteo"}"#)
            )
            .is_err()
        );
        assert!(
            put(
                &config,
                &change(r#"{"previous":"gone","id":"x","integration":"open-meteo"}"#)
            )
            .is_err()
        );
    }

    #[test]
    fn a_refused_file_is_put_back() {
        let (_dir, config) = house("rollback", "");
        let before = std::fs::read_to_string(&config).unwrap();
        put(
            &config,
            &change(r#"{"id":"meteo","integration":"open-meteo"}"#),
        )
        .unwrap();
        rollback(&config).unwrap();
        assert_eq!(std::fs::read_to_string(&config).unwrap(), before);
        put(
            &config,
            &change(r#"{"id":"meteo","integration":"open-meteo"}"#),
        )
        .unwrap();
        settle(&config);
        assert!(!previous(&config).exists());
        assert!(std::fs::read_to_string(&config).unwrap().contains("meteo"));
    }

    #[test]
    fn secrets_go_to_the_official_vault_and_are_never_read_back() {
        let (_dir, config) = house("vault", "");
        let key = MasterKey::generate().unwrap();
        let names = vec!["key".to_owned(), "token".to_owned()];
        assert!(stored(&config, &key, "prise", &names).unwrap().is_empty());
        let values = BTreeMap::from([
            ("key".to_owned(), " abc ".to_owned()),
            ("token".to_owned(), String::new()),
        ]);
        store(&config, &key, "prise", &values).unwrap();
        assert_eq!(stored(&config, &key, "prise", &names).unwrap(), ["key"]);
        let vault = config.with_file_name("secrets.enc");
        assert_eq!(
            moli_runtime::get_secret(&vault, &key, "prise/key")
                .unwrap()
                .as_deref(),
            Some("abc")
        );
        forget(&config, &key, "prise", &names).unwrap();
        assert!(stored(&config, &key, "prise", &names).unwrap().is_empty());
    }

    fn health(port: u16) -> Option<String> {
        use std::io::{Read as _, Write as _};
        let mut socket = std::net::TcpStream::connect(("127.0.0.1", port)).ok()?;
        socket
            .set_read_timeout(Some(std::time::Duration::from_secs(5)))
            .ok()?;
        write!(
            socket,
            "GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        .ok()?;
        let mut response = String::new();
        socket.read_to_string(&mut response).ok()?;
        Some(response).filter(|r| r.starts_with("HTTP/1.1 200"))
    }

    #[test]
    fn the_official_engine_runs_what_the_screen_writes_and_refuses_the_rest() {
        let port = std::net::TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let (_dir, config) = house("engine", "");
        let text = std::fs::read_to_string(&config).unwrap();
        std::fs::write(
            &config,
            text.replace(
                "[server]\n",
                &format!("[server]\nlisten = \"127.0.0.1:{port}\"\n"),
            ),
        )
        .unwrap();
        let key = MasterKey::generate().unwrap();
        put(
            &config,
            &change(
                r#"{"id":"meteo","integration":"open-meteo","vars":{"lat":"48.85","lon":"2.35"}}"#,
            ),
        )
        .unwrap();
        let moli = moli_os::hosted::Hosted::start(&config, key.clone(), "482913".into()).unwrap();
        let end = std::time::Instant::now() + std::time::Duration::from_secs(20);
        let mut seen = None;
        while std::time::Instant::now() < end {
            seen = health(port);
            if seen.is_some() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        let seen = seen.expect("Moli never answered");
        assert!(seen.contains(r#""instance":"meteo""#), "{seen}");
        moli.stop().unwrap();

        // A block the engine refuses (an option of the wrong type): Moli does
        // not run with it, and the previous file comes back.
        settle(&config);
        put(
            &config,
            &change(r#"{"id":"kasa","integration":"kasa","fields":{"host":"192.168.1.20","port":"not a port"}}"#),
        )
        .unwrap();
        let refused = moli_os::hosted::Hosted::start(&config, key.clone(), "482913".into())
            .and_then(|m| {
                let end = std::time::Instant::now() + std::time::Duration::from_secs(20);
                while m.running() && std::time::Instant::now() < end {
                    std::thread::sleep(std::time::Duration::from_millis(100));
                }
                m.stop()
            });
        assert!(refused.is_err(), "the engine took a bad block");
        rollback(&config).unwrap();
        assert!(!std::fs::read_to_string(&config).unwrap().contains("kasa"));
    }
}
