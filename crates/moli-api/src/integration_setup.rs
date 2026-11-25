//! Integrations set up from the dashboard: the catalogue the binary carries
//! (each package's manifest and onboarding), the ones this house added, and
//! adding, changing or removing one. Humans only, and only at home (no remote
//! entry). The executable owns what is stored and each driver's lifetime; a
//! secret typed here goes to the vault and never comes back out.

use std::collections::BTreeMap;
use std::fmt::Debug;
use std::sync::Arc;

use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::{Extension, Json};
use moli_core::Origin;
use moli_runtime::{BoxFuture, Hub};
use serde::{Deserialize, Serialize};

use crate::{caller::Caller, rest::origin, session::Sessions};

/// Longest value a person may type in one field (a token, a long key).
pub const MAX_VALUE: usize = 4096;
/// Longest advanced block (options written as TOML).
pub const MAX_ADVANCED: usize = 16_384;
/// Fields one integration may carry.
pub const MAX_FIELDS: usize = 64;

/// One package of the catalogue, as the dashboard shows it.
#[derive(Clone, Debug, Serialize)]
pub struct Package {
    pub id: String,
    pub name: String,
    pub brands: Vec<String>,
    pub models: Vec<String>,
    pub transport: String,
    /// The driver it runs: `profile`, or a native kind.
    pub kind: String,
    /// What a person gives: secrets (to the vault), variables of a profile,
    /// options of the driver.
    pub secrets: Vec<String>,
    pub vars: Vec<String>,
    pub options: Vec<String>,
    /// The package's onboarding guide (Markdown), as written in the repository.
    pub onboarding: String,
    /// Can be set up from the dashboard (some need their own page or the
    /// configuration file).
    pub addable: bool,
}

/// One integration this house added from the dashboard. Its secrets are
/// named, never given back.
#[derive(Clone, Debug, Serialize)]
pub struct Installed {
    pub instance: String,
    pub integration: String,
    pub vars: BTreeMap<String, String>,
    pub options: BTreeMap<String, String>,
    pub advanced: String,
    pub secrets: Vec<String>,
    pub enabled: bool,
}

/// « Set it up »: what the person typed. An empty secret keeps the one
/// stored; `advanced` is TOML merged into the options, for what a single
/// field cannot say (lists, tables).
#[derive(Clone, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Install {
    pub integration: String,
    #[serde(default)]
    pub instance: Option<String>,
    #[serde(default)]
    pub vars: BTreeMap<String, String>,
    #[serde(default)]
    pub options: BTreeMap<String, String>,
    #[serde(default)]
    pub advanced: String,
    #[serde(default)]
    pub secrets: BTreeMap<String, String>,
    #[serde(default = "enabled")]
    pub enabled: bool,
}

fn enabled() -> bool {
    true
}

impl Debug for Install {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Install")
            .field("integration", &self.integration)
            .field("instance", &self.instance)
            .field("vars", &self.vars)
            .field("options", &self.options)
            .field("advanced", &self.advanced.len())
            .field("secrets", &self.secrets.keys().collect::<Vec<_>>())
            .field("enabled", &self.enabled)
            .finish()
    }
}

/// Why a setup was refused. `detail`: what the driver said of its options,
/// in its words (never a secret: secrets are not options).
#[derive(Clone, Debug, Serialize)]
pub struct IntegrationError {
    #[serde(rename = "error")]
    pub code: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

impl IntegrationError {
    pub fn new(code: &'static str) -> Self {
        Self { code, detail: None }
    }

    pub fn with(code: &'static str, detail: impl Into<String>) -> Self {
        Self {
            code,
            detail: Some(detail.into()),
        }
    }
}

impl IntoResponse for IntegrationError {
    fn into_response(self) -> Response {
        let status = match self.code {
            "duplicate_instance" | "configuration_locked" => StatusCode::CONFLICT,
            "not_found" => StatusCode::NOT_FOUND,
            "busy" | "limit_reached" => StatusCode::TOO_MANY_REQUESTS,
            "persistence_failed" => StatusCode::INTERNAL_SERVER_ERROR,
            "unavailable" | "shutting_down" | "no_vault" => StatusCode::SERVICE_UNAVAILABLE,
            _ => StatusCode::UNPROCESSABLE_ENTITY,
        };
        (status, Json(self)).into_response()
    }
}

pub trait IntegrationSetup: Send + Sync + Debug {
    fn catalogue(&self) -> Vec<Package>;
    fn installed(&self) -> BoxFuture<'_, Vec<Installed>>;
    /// Adds one (`instance` none), or replaces the one named.
    fn install(
        &self,
        instance: Option<String>,
        request: Install,
        actor: String,
    ) -> BoxFuture<'_, Result<Installed, IntegrationError>>;
    fn remove(
        &self,
        instance: String,
        actor: String,
    ) -> BoxFuture<'_, Result<(), IntegrationError>>;
}

#[derive(Clone)]
pub(crate) struct Setup(pub Option<Arc<dyn IntegrationSetup>>);

fn printable(value: &str, max: usize) -> bool {
    value.len() <= max
        && !value
            .chars()
            .any(|c| c.is_control() && c != '\n' && c != '\t')
}

fn name_ok(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 64
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

fn request_ok(request: &Install) -> Result<(), IntegrationError> {
    if !name_ok(&request.integration) {
        return Err(IntegrationError::new("unknown_integration"));
    }
    if request.instance.as_deref().is_some_and(|i| !name_ok(i)) {
        return Err(IntegrationError::new("invalid_instance"));
    }
    let fields = request.vars.len() + request.options.len() + request.secrets.len();
    if fields > MAX_FIELDS {
        return Err(IntegrationError::new("invalid_field"));
    }
    for (name, value) in request
        .vars
        .iter()
        .chain(&request.options)
        .chain(&request.secrets)
    {
        if !name_ok(name) || !printable(value, MAX_VALUE) || value.contains('\n') {
            return Err(IntegrationError::new("invalid_field"));
        }
    }
    if !printable(&request.advanced, MAX_ADVANCED) {
        return Err(IntegrationError::new("invalid_advanced"));
    }
    Ok(())
}

fn person(hub: &Hub, headers: &HeaderMap, humans: &Sessions, caller: &Caller) -> bool {
    origin(hub, headers, humans, caller) == Origin::Ui
}

pub(crate) async fn catalogue(
    State(hub): State<Hub>,
    Extension(humans): Extension<Arc<Sessions>>,
    Extension(Setup(setup)): Extension<Setup>,
    caller: Caller,
    headers: HeaderMap,
) -> Response {
    if !person(&hub, &headers, &humans, &caller) {
        return StatusCode::FORBIDDEN.into_response();
    }
    match setup {
        Some(setup) => Json(setup.catalogue()).into_response(),
        None => IntegrationError::new("unavailable").into_response(),
    }
}

pub(crate) async fn installed(
    State(hub): State<Hub>,
    Extension(humans): Extension<Arc<Sessions>>,
    Extension(Setup(setup)): Extension<Setup>,
    caller: Caller,
    headers: HeaderMap,
) -> Response {
    if !person(&hub, &headers, &humans, &caller) {
        return StatusCode::FORBIDDEN.into_response();
    }
    match setup {
        Some(setup) => Json(setup.installed().await).into_response(),
        None => IntegrationError::new("unavailable").into_response(),
    }
}

pub(crate) async fn add(
    State(hub): State<Hub>,
    Extension(humans): Extension<Arc<Sessions>>,
    Extension(Setup(setup)): Extension<Setup>,
    caller: Caller,
    headers: HeaderMap,
    Json(request): Json<Install>,
) -> Response {
    if !person(&hub, &headers, &humans, &caller) {
        return StatusCode::FORBIDDEN.into_response();
    }
    if let Err(error) = request_ok(&request) {
        return error.into_response();
    }
    let Some(setup) = setup else {
        return IntegrationError::new("unavailable").into_response();
    };
    match setup.install(None, request, caller.describe()).await {
        Ok(installed) => Json(installed).into_response(),
        Err(error) => error.into_response(),
    }
}

pub(crate) async fn replace(
    State(hub): State<Hub>,
    Extension(humans): Extension<Arc<Sessions>>,
    Extension(Setup(setup)): Extension<Setup>,
    caller: Caller,
    headers: HeaderMap,
    Path(instance): Path<String>,
    Json(request): Json<Install>,
) -> Response {
    if !person(&hub, &headers, &humans, &caller) {
        return StatusCode::FORBIDDEN.into_response();
    }
    if !name_ok(&instance) {
        return IntegrationError::new("not_found").into_response();
    }
    if let Err(error) = request_ok(&request) {
        return error.into_response();
    }
    let Some(setup) = setup else {
        return IntegrationError::new("unavailable").into_response();
    };
    match setup
        .install(Some(instance), request, caller.describe())
        .await
    {
        Ok(installed) => Json(installed).into_response(),
        Err(error) => error.into_response(),
    }
}

pub(crate) async fn remove(
    State(hub): State<Hub>,
    Extension(humans): Extension<Arc<Sessions>>,
    Extension(Setup(setup)): Extension<Setup>,
    caller: Caller,
    headers: HeaderMap,
    Path(instance): Path<String>,
) -> Response {
    if !person(&hub, &headers, &humans, &caller) {
        return StatusCode::FORBIDDEN.into_response();
    }
    if !name_ok(&instance) {
        return IntegrationError::new("not_found").into_response();
    }
    let Some(setup) = setup else {
        return IntegrationError::new("unavailable").into_response();
    };
    match setup.remove(instance, caller.describe()).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(error) => error.into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request() -> Install {
        Install {
            integration: "hue".into(),
            ..Install::default()
        }
    }

    #[test]
    fn a_request_is_bounded_and_named_plainly() {
        assert!(request_ok(&request()).is_ok());
        let mut bad = request();
        bad.integration = "../hue".into();
        assert_eq!(request_ok(&bad).unwrap_err().code, "unknown_integration");
        let mut bad = request();
        bad.options.insert("host".into(), "a\nb".into());
        assert_eq!(request_ok(&bad).unwrap_err().code, "invalid_field");
        let mut bad = request();
        bad.secrets.insert("key".into(), "x".repeat(MAX_VALUE + 1));
        assert_eq!(request_ok(&bad).unwrap_err().code, "invalid_field");
        let mut bad = request();
        bad.instance = Some("bad id".into());
        assert_eq!(request_ok(&bad).unwrap_err().code, "invalid_instance");
        let mut ok = request();
        ok.advanced = "[[helper]]\nid = \"mode\"\n".into();
        assert!(request_ok(&ok).is_ok());
    }

    #[test]
    fn a_secret_never_shows_in_debug_output() {
        let mut typed = request();
        typed.secrets.insert("password".into(), "hunter2".into());
        assert!(!format!("{typed:?}").contains("hunter2"));
    }
}
