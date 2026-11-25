//! Integrations a person set up from the dashboard (`data/integrations-added.json`).
//!
//! The same way as a device added by its address (`managed_devices`): the
//! hand-written configuration stays untouched, this sidecar lists each
//! integration (its package, the values typed, the names of its secrets),
//! and each one runs as any configured driver (`build_driver`), started and
//! stopped without restarting Moli. Secrets go to the vault, never into the
//! file. A file that cannot be read is never rewritten: what it lists is not
//! started, the rest of the house runs, and changes wait for a person to
//! repair it.

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock};
use std::time::Duration;

use moli_api::integration_setup::{
    Install, Installed, IntegrationError, IntegrationSetup, Package,
};
use moli_core::InstanceId;
use moli_runtime::{BoxFuture, Hub, spawn_driver};
use serde::{Deserialize, Serialize};
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

use crate::config::{Config, DriverConfig};

mod packages {
    include!(concat!(env!("OUT_DIR"), "/packages.rs"));
}

pub const FILE: &str = "integrations-added.json";
const VERSION: u8 = 1;
const MAX_INTEGRATIONS: usize = 64;
const MAX_BYTES: u64 = 1_048_576;
/// How long a stopping driver is waited for.
const STOP_WAIT: Duration = Duration::from_secs(10);
/// Kinds with a page or a wiring of their own: not set up here.
const ELSEWHERE: &[&str] = &["airzone", "phones", "host", "demo"];

#[derive(Debug, Deserialize)]
struct Manifest {
    integration: Head,
    #[serde(default)]
    needs: Needs,
}

#[derive(Debug, Deserialize)]
struct Head {
    id: String,
    name: String,
    #[serde(default)]
    brands: Vec<String>,
    #[serde(default)]
    models: Vec<String>,
    transport: String,
    driver: String,
}

#[derive(Debug, Default, Deserialize)]
struct Needs {
    #[serde(default)]
    secrets: Vec<String>,
    #[serde(default)]
    vars: Vec<String>,
    #[serde(default)]
    options: Vec<String>,
}

/// The catalogue, read once from what the binary carries.
static CATALOGUE: LazyLock<Vec<Package>> = LazyLock::new(|| {
    packages::PACKAGES
        .iter()
        .filter_map(|(id, manifest, onboarding)| {
            let manifest: Manifest = match toml::from_str(manifest) {
                Ok(manifest) => manifest,
                Err(e) => {
                    tracing::warn!(package = %id, error = %e, "integration manifest unreadable");
                    return None;
                }
            };
            let head = manifest.integration;
            let profile = head.driver == "profile";
            let addable = if profile {
                moli_profile::builtin(&head.id).is_some()
            } else {
                crate::KINDS.contains(&head.driver.as_str())
                    && !ELSEWHERE.contains(&head.driver.as_str())
            };
            Some(Package {
                id: head.id,
                name: head.name,
                brands: head.brands,
                models: head.models,
                transport: head.transport,
                kind: head.driver,
                secrets: manifest.needs.secrets,
                vars: manifest.needs.vars,
                options: manifest.needs.options,
                onboarding: (*onboarding).to_owned(),
                addable,
            })
        })
        .collect()
});

fn package(id: &str) -> Option<&'static Package> {
    CATALOGUE.iter().find(|p| p.id == id)
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
struct Added {
    instance: String,
    integration: String,
    #[serde(default)]
    vars: BTreeMap<String, String>,
    #[serde(default)]
    options: BTreeMap<String, String>,
    #[serde(default)]
    advanced: String,
    /// The names of the secrets stored for it (the values are in the vault).
    #[serde(default)]
    secrets: BTreeSet<String>,
    #[serde(default = "yes")]
    enabled: bool,
}

fn yes() -> bool {
    true
}

impl Added {
    fn shown(&self) -> Installed {
        Installed {
            instance: self.instance.clone(),
            integration: self.integration.clone(),
            vars: self.vars.clone(),
            options: self.options.clone(),
            advanced: self.advanced.clone(),
            secrets: self.secrets.iter().cloned().collect(),
            enabled: self.enabled,
        }
    }

    /// The driver as `moli.toml` would declare it.
    fn driver(&self) -> Result<DriverConfig, IntegrationError> {
        let package = package(&self.integration)
            .filter(|p| p.addable)
            .ok_or_else(|| IntegrationError::new("unknown_integration"))?;
        let mut options = toml::Table::new();
        for (name, value) in &self.options {
            if !value.trim().is_empty() {
                options.insert(name.clone(), typed(value.trim()));
            }
        }
        if package.kind == "profile" {
            options.insert("profile".into(), package.id.clone().into());
            let vars: toml::Table = self
                .vars
                .iter()
                .filter(|(_, v)| !v.trim().is_empty())
                .map(|(k, v)| (k.clone(), toml::Value::String(v.trim().to_owned())))
                .collect();
            if !vars.is_empty() {
                options.insert("vars".into(), toml::Value::Table(vars));
            }
        }
        if !self.advanced.trim().is_empty() {
            let extra: toml::Table = toml::from_str(&self.advanced)
                .map_err(|e| IntegrationError::with("invalid_advanced", e.message().to_owned()))?;
            options.extend(extra);
        }
        Ok(DriverConfig {
            id: self.instance.clone(),
            kind: package.kind.clone(),
            enabled: Some(self.enabled),
            options: toml::Value::Table(options),
        })
    }
}

/// A typed field as TOML would read it: `true`/`false`, a whole number, else text.
fn typed(value: &str) -> toml::Value {
    match value {
        "true" => toml::Value::Boolean(true),
        "false" => toml::Value::Boolean(false),
        _ => match value.parse::<i64>() {
            Ok(n) if !(value.len() > 1 && value.starts_with('0')) => toml::Value::Integer(n),
            _ => toml::Value::String(value.to_owned()),
        },
    }
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Store {
    version: u8,
    integrations: Vec<Added>,
}

/// What the file lists, and whether it may be rewritten (it was read whole).
fn read_store(path: &Path) -> (Vec<Added>, bool) {
    let text = match std::fs::metadata(path) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return (Vec::new(), true),
        Ok(meta) if meta.len() <= MAX_BYTES => std::fs::read_to_string(path),
        Ok(_) => Err(std::io::Error::other("too large")),
        Err(e) => Err(e),
    };
    match text
        .map_err(|e| e.to_string())
        .and_then(|t| serde_json::from_str::<Store>(&t).map_err(|e| e.to_string()))
    {
        Ok(store) if store.version == VERSION => (store.integrations, true),
        Ok(_) => {
            tracing::error!(path = %path.display(), "integrations file of another version: left as it is");
            (Vec::new(), false)
        }
        Err(e) => {
            tracing::error!(path = %path.display(), error = %e, "integrations file unreadable: left as it is, nothing it lists starts");
            (Vec::new(), false)
        }
    }
}

/// Written aside, synced, then moved in place: never half a file.
fn write_store(path: &Path, integrations: &[Added]) -> std::io::Result<()> {
    let text = serde_json::to_string_pretty(&Store {
        version: VERSION,
        integrations: integrations.to_vec(),
    })?;
    let aside = path.with_extension("json.new");
    {
        let mut file = std::fs::File::create(&aside)?;
        std::io::Write::write_all(&mut file, text.as_bytes())?;
        file.sync_all()?;
    }
    std::fs::rename(&aside, path)
}

/// The instances a device added by its address uses (they are taken).
fn devices_added(data_dir: &Path) -> BTreeSet<String> {
    #[derive(Deserialize)]
    struct Device {
        instance: String,
    }
    #[derive(Deserialize)]
    struct Devices {
        #[serde(default)]
        devices: Vec<Device>,
    }
    std::fs::read_to_string(data_dir.join(crate::managed_devices::FILE))
        .ok()
        .and_then(|t| serde_json::from_str::<Devices>(&t).ok())
        .map(|d| d.devices.into_iter().map(|d| d.instance).collect())
        .unwrap_or_default()
}

fn valid_instance(instance: &str) -> bool {
    !instance.is_empty()
        && instance.len() <= 64
        && instance
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'_')
}

struct Running {
    cancel: CancellationToken,
    task: JoinHandle<()>,
}

struct State {
    integrations: Vec<Added>,
    editable: bool,
    running: HashMap<String, Running>,
}

struct Inner {
    hub: Hub,
    path: PathBuf,
    data_dir: PathBuf,
    cancel: CancellationToken,
    /// The configuration file's drivers (never set up here).
    configured: BTreeSet<String>,
    state: tokio::sync::Mutex<State>,
}

#[derive(Clone)]
pub struct Manager(Arc<Inner>);

impl std::fmt::Debug for Manager {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ManagedIntegrations")
            .field("path", &self.0.path)
            .finish_non_exhaustive()
    }
}

impl Manager {
    /// What the file lists, started.
    pub fn start(hub: Hub, config: &Config, cancel: CancellationToken) -> Self {
        let data_dir = config.server.data_dir.clone();
        let path = data_dir.join(FILE);
        let configured: BTreeSet<String> = config.drivers.iter().map(|d| d.id.clone()).collect();
        let (listed, mut editable) = read_store(&path);
        let mut integrations: Vec<Added> = Vec::new();
        for added in listed {
            let taken = configured.contains(&added.instance)
                || integrations.iter().any(|a| a.instance == added.instance);
            if taken || !valid_instance(&added.instance) || added.driver().is_err() {
                tracing::warn!(instance = %added.instance, "ignoring an integration set up from the dashboard: invalid or taken; changes paused until it is repaired");
                editable = false;
                continue;
            }
            integrations.push(added);
        }
        let manager = Self(Arc::new(Inner {
            hub,
            path,
            data_dir,
            cancel,
            configured,
            state: tokio::sync::Mutex::new(State {
                integrations: integrations.clone(),
                editable,
                running: HashMap::new(),
            }),
        }));
        if let Ok(mut state) = manager.0.state.try_lock() {
            for added in integrations.iter().filter(|a| a.enabled) {
                match manager.spawn(added) {
                    Ok(running) => {
                        state.running.insert(added.instance.clone(), running);
                    }
                    Err(e) => {
                        tracing::warn!(instance = %added.instance, error = ?e, "cannot start an integration set up from the dashboard");
                    }
                }
            }
        }
        manager
    }

    fn spawn(&self, added: &Added) -> Result<Running, IntegrationError> {
        let config = added.driver()?;
        let driver = crate::build_driver(&config, &self.0.data_dir)
            .map_err(|e| IntegrationError::with("invalid_options", format!("{e:#}")))?;
        let cancel = self.0.cancel.child_token();
        let task = spawn_driver(
            &self.0.hub,
            InstanceId::from(added.instance.as_str()),
            driver,
            cancel.clone(),
        );
        Ok(Running { cancel, task })
    }

    /// Waits for the drivers it started (after the cancellation).
    pub async fn shutdown(&self) {
        let running: Vec<Running> = self
            .0
            .state
            .lock()
            .await
            .running
            .drain()
            .map(|(_, r)| r)
            .collect();
        for running in running {
            let _ = running.task.await;
        }
    }

    fn next_instance(&self, base: &str, state: &State) -> String {
        let taken = |name: &str| {
            self.0.configured.contains(name)
                || state.integrations.iter().any(|a| a.instance == name)
                || devices_added(&self.0.data_dir).contains(name)
        };
        let base: String = base
            .to_ascii_lowercase()
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || c == '-' {
                    c
                } else {
                    '-'
                }
            })
            .collect();
        if !taken(&base) {
            return base;
        }
        (2..=10_000)
            .map(|n| format!("{base}-{n}"))
            .find(|name| !taken(name))
            .unwrap_or_else(|| format!("{base}-{}", state.integrations.len() + 10_001))
    }

    /// The instance a setup goes to: the one changed, the one asked for, or a
    /// new name from the package's.
    fn instance_for(
        &self,
        instance: Option<String>,
        wanted: Option<&String>,
        package: &Package,
        state: &State,
    ) -> Result<String, IntegrationError> {
        match (instance, wanted) {
            (Some(name), _) => Ok(name),
            (None, Some(wanted)) => {
                let wanted = wanted.to_ascii_lowercase();
                if !valid_instance(&wanted)
                    || self.0.configured.contains(&wanted)
                    || state.integrations.iter().any(|a| a.instance == wanted)
                    || devices_added(&self.0.data_dir).contains(&wanted)
                {
                    return Err(IntegrationError::new("duplicate_instance"));
                }
                Ok(wanted)
            }
            (None, None) => Ok(self.next_instance(&package.id, state)),
        }
    }

    async fn install_now(
        &self,
        instance: Option<String>,
        request: Install,
        actor: String,
    ) -> Result<Installed, IntegrationError> {
        let package = package(&request.integration)
            .ok_or_else(|| IntegrationError::new("unknown_integration"))?;
        if !package.addable {
            return Err(IntegrationError::new("not_addable"));
        }
        let mut state = self.0.state.lock().await;
        if !state.editable {
            return Err(IntegrationError::new("configuration_locked"));
        }
        let previous = match &instance {
            Some(name) => Some(
                state
                    .integrations
                    .iter()
                    .position(|a| &a.instance == name)
                    .ok_or_else(|| IntegrationError::new("not_found"))?,
            ),
            None => None,
        };
        if previous.is_none() && state.integrations.len() >= MAX_INTEGRATIONS {
            return Err(IntegrationError::new("limit_reached"));
        }
        let name = self.instance_for(instance, request.instance.as_ref(), package, &state)?;
        if let Some(at) = previous
            && state.integrations[at].integration != package.id
        {
            return Err(IntegrationError::new("unknown_integration"));
        }
        let mut secrets: BTreeSet<String> = previous
            .map(|at| state.integrations[at].secrets.clone())
            .unwrap_or_default();
        let typed_secrets: Vec<(&String, &String)> = request
            .secrets
            .iter()
            .filter(|(_, v)| !v.is_empty())
            .collect();
        for (secret, _) in &typed_secrets {
            if !package.secrets.iter().any(|s| s == *secret) {
                return Err(IntegrationError::new("invalid_field"));
            }
        }
        let added = Added {
            instance: name.clone(),
            integration: package.id.clone(),
            vars: request
                .vars
                .into_iter()
                .filter(|(_, v)| !v.trim().is_empty())
                .collect(),
            options: request
                .options
                .into_iter()
                .filter(|(_, v)| !v.trim().is_empty())
                .collect(),
            advanced: request.advanced,
            secrets: BTreeSet::new(),
            enabled: request.enabled,
        };
        // The options are checked as the driver reads them before anything changes.
        let config = added.driver()?;
        crate::build_driver(&config, &self.0.data_dir)
            .map_err(|e| IntegrationError::with("invalid_options", format!("{e:#}")))?;
        let instance_id = InstanceId::from(name.as_str());
        for (secret, value) in typed_secrets {
            if !self.0.hub.has_secret_store() {
                return Err(IntegrationError::new("no_vault"));
            }
            self.0
                .hub
                .store_secret(&instance_id, secret, value)
                .await
                .map_err(|_| IntegrationError::new("persistence_failed"))?;
            secrets.insert(secret.clone());
        }
        let added = Added { secrets, ..added };
        let mut next = state.integrations.clone();
        match previous {
            Some(at) => next[at] = added.clone(),
            None => next.push(added.clone()),
        }
        write_store(&self.0.path, &next)
            .map_err(|_| IntegrationError::new("persistence_failed"))?;
        state.integrations = next;
        if let Some(old) = state.running.remove(&name) {
            old.cancel.cancel();
            let _ = tokio::time::timeout(STOP_WAIT, old.task).await;
        }
        if added.enabled {
            let running = self.spawn(&added)?;
            state.running.insert(name.clone(), running);
        }
        tracing::info!(instance = %name, integration = %added.integration, by = %actor, "integration set up from the dashboard");
        Ok(added.shown())
    }

    async fn remove_now(&self, instance: String, actor: String) -> Result<(), IntegrationError> {
        let mut state = self.0.state.lock().await;
        if !state.editable {
            return Err(IntegrationError::new("configuration_locked"));
        }
        let at = state
            .integrations
            .iter()
            .position(|a| a.instance == instance)
            .ok_or_else(|| IntegrationError::new("not_found"))?;
        let mut next = state.integrations.clone();
        next.remove(at);
        write_store(&self.0.path, &next)
            .map_err(|_| IntegrationError::new("persistence_failed"))?;
        state.integrations = next;
        if let Some(old) = state.running.remove(&instance) {
            old.cancel.cancel();
            let _ = tokio::time::timeout(STOP_WAIT, old.task).await;
        }
        tracing::info!(instance = %instance, by = %actor, "integration removed from the dashboard");
        Ok(())
    }
}

impl IntegrationSetup for Manager {
    fn catalogue(&self) -> Vec<Package> {
        CATALOGUE.clone()
    }

    fn installed(&self) -> BoxFuture<'_, Vec<Installed>> {
        Box::pin(async move {
            self.0
                .state
                .lock()
                .await
                .integrations
                .iter()
                .map(Added::shown)
                .collect()
        })
    }

    fn install(
        &self,
        instance: Option<String>,
        request: Install,
        actor: String,
    ) -> BoxFuture<'_, Result<Installed, IntegrationError>> {
        Box::pin(self.install_now(instance, request, actor))
    }

    fn remove(
        &self,
        instance: String,
        actor: String,
    ) -> BoxFuture<'_, Result<(), IntegrationError>> {
        Box::pin(self.remove_now(instance, actor))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_catalogue_is_carried_and_says_what_can_be_set_up() {
        let hue = package("hue").expect("hue is in the catalogue");
        assert!(hue.addable);
        assert_eq!(hue.kind, "hue");
        let meross = package("meross-mss310").expect("a profile package");
        assert!(meross.addable && meross.kind == "profile");
        assert!(meross.secrets.iter().any(|s| s == "key"));
        assert!(!meross.onboarding.is_empty());
        assert!(!package("airzone").is_some_and(|p| p.addable));
        assert!(CATALOGUE.len() > 40);
    }

    #[test]
    fn fields_are_typed_as_toml_reads_them() {
        assert_eq!(typed("1883"), toml::Value::Integer(1883));
        assert_eq!(typed("true"), toml::Value::Boolean(true));
        assert_eq!(typed("0123"), toml::Value::String("0123".into()));
        assert_eq!(
            typed("192.168.1.5"),
            toml::Value::String("192.168.1.5".into())
        );
    }

    #[test]
    fn a_profile_is_declared_as_in_the_configuration_file() {
        let added = Added {
            instance: "prise".into(),
            integration: "meross-mss310".into(),
            vars: BTreeMap::new(),
            options: BTreeMap::from([("host".into(), "192.168.1.20".into())]),
            advanced: "empty_secret = true\n".into(),
            secrets: BTreeSet::new(),
            enabled: true,
        };
        let driver = added.driver().unwrap();
        assert_eq!(driver.kind, "profile");
        assert_eq!(
            driver.options.get("profile").and_then(toml::Value::as_str),
            Some("meross-mss310")
        );
        assert_eq!(
            driver.options.get("host").and_then(toml::Value::as_str),
            Some("192.168.1.20")
        );
        assert_eq!(
            driver
                .options
                .get("empty_secret")
                .and_then(toml::Value::as_bool),
            Some(true)
        );
        assert!(crate::build_driver(&driver, Path::new("/tmp")).is_ok());
        let mut bad = added;
        bad.advanced = "not toml =".into();
        assert_eq!(bad.driver().unwrap_err().code, "invalid_advanced");
    }

    #[test]
    fn an_unreadable_file_is_left_as_it_is() {
        let dir = std::env::temp_dir().join(format!("moli-integrations-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(FILE);
        assert_eq!(read_store(&path), (Vec::new(), true));
        std::fs::write(&path, "{ not json").unwrap();
        assert_eq!(read_store(&path), (Vec::new(), false));
        let added = Added {
            instance: "hue".into(),
            integration: "hue".into(),
            vars: BTreeMap::new(),
            options: BTreeMap::new(),
            advanced: String::new(),
            secrets: BTreeSet::from(["token".to_owned()]),
            enabled: false,
        };
        write_store(&path, std::slice::from_ref(&added)).unwrap();
        assert_eq!(read_store(&path), (vec![added], true));
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
