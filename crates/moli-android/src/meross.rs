//! The key of a Meross account, read once from the account (DECISIONS 7),
//! with the list of its devices (their names in the Meross app):
//! sign in as `meross_lan` does (`/v1/Auth/signIn`, the region's server,
//! its redirection followed), keep the key, read `/v1/Device/devList`, sign out at once
//! (`/v1/Profile/Logout`). The password is neither kept nor written
//! anywhere. Protocol knowledge from `meross_lan` (MIT): envelope, signature,
//! statuses; no code copied.
//!
//! The key is the same for every device of the account: the official
//! « Ajouter un appareil » takes it with the first plug, then reuses it for
//! the next ones by itself.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context as _, bail};
use base64::prelude::{BASE64_STANDARD, Engine as _};
use md5::{Digest as _, Md5};
use moli_net::Method;
use ring::rand::{SecureRandom as _, SystemRandom};
use serde_json::{Value as Json, json};

/// The app's signing secret, the same in every Meross client.
const SECRET: &str = "23x17ahWarFH6w29";
/// The European server first; an account of another region is redirected
/// (status 1030).
const SERVER: &str = "https://iotx-eu.meross.com";
const REGION: &str = "eu";
const SIGN_IN: &str = "/v1/Auth/signIn";
const SIGN_OUT: &str = "/v1/Profile/Logout";
const DEVICES: &str = "/v1/Device/devList";
const REDIRECTED: i64 = 1030;
const LIMIT: Duration = Duration::from_secs(10);
const MAX_BODY: usize = 64 * 1024;
/// Longest key the official « Ajouter un appareil » takes (`MAX_KEY`).
const MAX_KEY: usize = 128;

/// What the screen sends: the account, and the code Meross sent when it asks for one.
#[derive(serde::Deserialize)]
pub struct Account {
    pub email: String,
    pub password: String,
    #[serde(default)]
    pub code: Option<String>,
}

impl std::fmt::Debug for Account {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Account").finish_non_exhaustive()
    }
}

/// The key and the account's devices (`{"key": …, "devices": [{uuid, name,
/// model, online}]}`), or Meross's refusal (`{"status": 1004, "info": …}`):
/// the screen says it in words. A network failure is an error.
pub fn key(account: &Account) -> anyhow::Result<Json> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    runtime.block_on(key_from(SERVER, account))
}

async fn key_from(server: &str, account: &Account) -> anyhow::Result<Json> {
    let mut request = json!({
        "email": account.email.trim(),
        "password": hex(&Md5::digest(account.password.as_bytes())),
        "accountCountryCode": REGION,
        "encryption": 1,
        "agree": 0,
    });
    if let Some(code) = account
        .code
        .as_deref()
        .map(str::trim)
        .filter(|c| !c.is_empty())
    {
        request["mfaCode"] = code.into();
    }
    let mut server = server.to_owned();
    let mut answer = post(&server, SIGN_IN, &request, None).await?;
    if status(&answer) == REDIRECTED {
        server = text(&answer["data"]["domain"])
            .context("Meross redirected without a server")?
            .to_owned();
        answer = post(&server, SIGN_IN, &request, None).await?;
    }
    let refused = status(&answer);
    if refused != 0 {
        return Ok(json!({ "status": refused, "info": text(&answer["info"]).unwrap_or("") }));
    }
    let data = &answer["data"];
    let key = text(&data["key"]).context("Meross gave no key")?;
    if key.len() > MAX_KEY || key.chars().any(char::is_control) {
        bail!("Meross gave a key of an unknown form");
    }
    // The account's devices (their names in the Meross app), then the
    // session closed, as meross_lan does: a failure of either changes
    // nothing for the key.
    let mut devices = Vec::new();
    if let Some(token) = text(&data["token"]) {
        let server = text(&data["domain"]).unwrap_or(&server);
        if let Ok(list) = post(server, DEVICES, &json!({}), Some(token)).await {
            devices = devices_of(&list);
        }
        let _ = post(server, SIGN_OUT, &json!({}), Some(token)).await;
    }
    Ok(json!({ "key": key, "devices": devices }))
}

/// `/v1/Device/devList`: `[{uuid, name, model, online}]`, in the account's order.
fn devices_of(answer: &Json) -> Vec<Json> {
    if status(answer) != 0 {
        return Vec::new();
    }
    answer["data"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|d| {
            let uuid = text(&d["uuid"])?;
            Some(json!({
                "uuid": uuid,
                "name": text(&d["devName"]).unwrap_or(uuid),
                "model": text(&d["deviceType"]).unwrap_or(""),
                "online": d["onlineStatus"].as_i64() == Some(1),
            }))
        })
        .collect()
}

fn text(value: &Json) -> Option<&str> {
    value.as_str().filter(|s| !s.is_empty())
}

fn status(answer: &Json) -> i64 {
    answer["apiStatus"].as_i64().unwrap_or(0)
}

/// One call to the Meross API: the signed envelope, the JSON answer.
async fn post(
    server: &str,
    path: &str,
    params: &Json,
    token: Option<&str>,
) -> anyhow::Result<Json> {
    let (host, port, tls) = server_of(server)?;
    let mut nonce = [0u8; 16];
    SystemRandom::new()
        .fill(&mut nonce)
        .map_err(|_| anyhow::anyhow!("no randomness"))?;
    let now = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis();
    let body = envelope(params, now, &hex(&nonce));
    let mut builder = http::Request::builder().method(Method::POST).uri(path);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Basic {token}"));
    }
    let request = moli_net::json_body(builder, &body)?;
    let (code, answer) = moli_net::web(host, port, tls, request, LIMIT, MAX_BODY).await?;
    anyhow::ensure!(code.is_success(), "Meross: HTTP {}", code.as_u16());
    let answer: Json = serde_json::from_slice(&answer).context("Meross: not JSON")?;
    anyhow::ensure!(answer.is_object(), "Meross: not an object");
    Ok(answer)
}

/// `params` in base64, signed: `md5(secret ‖ timestamp ‖ nonce ‖ params)`.
fn envelope(params: &Json, timestamp: u128, nonce: &str) -> Json {
    let params = BASE64_STANDARD.encode(params.to_string());
    let sign = hex(&Md5::digest(
        format!("{SECRET}{timestamp}{nonce}{params}").as_bytes(),
    ));
    json!({ "timestamp": timestamp, "nonce": nonce, "params": params, "sign": sign })
}

/// `https://host[:port]` → host, port, TLS.
fn server_of(server: &str) -> anyhow::Result<(&str, u16, bool)> {
    let (rest, tls, port) = if let Some(rest) = server.strip_prefix("https://") {
        (rest, true, 443)
    } else if let Some(rest) = server.strip_prefix("http://") {
        (rest, false, 80)
    } else {
        (server, true, 443)
    };
    let rest = rest.split('/').next().unwrap_or(rest);
    match rest.rsplit_once(':') {
        Some((host, p)) => Ok((host, p.parse().context("Meross: bad server port")?, tls)),
        None => Ok((rest, port, tls)),
    }
}

pub(crate) fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    bytes.iter().fold(String::new(), |mut out, b| {
        let _ = write!(out, "{b:02x}");
        out
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::sync::{Arc, Mutex};

    use axum::Router;
    use axum::extract::State;
    use axum::http::HeaderMap;
    use axum::routing::post as route;

    /// Expected values computed independently (Python: `hashlib`, `base64`).
    #[test]
    fn the_envelope_is_signed_as_meross_reads_it() {
        let body = envelope(
            &json!({}),
            1_700_000_000_000,
            "0123456789abcdef0123456789abcdef",
        );
        assert_eq!(body["params"], "e30=");
        assert_eq!(body["sign"], "4d3f0feef5ee7c91b26aa07759d25d94");
    }

    #[test]
    fn servers_are_read_with_their_port() {
        assert_eq!(
            server_of("https://iotx-us.meross.com").unwrap(),
            ("iotx-us.meross.com", 443, true)
        );
        assert_eq!(
            server_of("http://127.0.0.1:8080").unwrap(),
            ("127.0.0.1", 8080, false)
        );
    }

    /// What the fake Meross received: (path, params decoded, authorization).
    type Seen = Arc<Mutex<Vec<(String, Json, Option<String>)>>>;

    /// One call the fake Meross received, kept.
    fn record(seen: &Seen, path: &str, headers: &HeaderMap, body: &str) {
        let body: Json = serde_json::from_str(body).unwrap();
        let params = BASE64_STANDARD
            .decode(body["params"].as_str().unwrap())
            .unwrap();
        let auth = headers
            .get("authorization")
            .map(|v| v.to_str().unwrap().to_owned());
        seen.lock().unwrap().push((
            path.to_owned(),
            serde_json::from_slice(&params).unwrap(),
            auth,
        ));
    }
    /// A fake Meross on two ports: the first redirects, the second answers
    /// `answer` to the sign-in and takes the sign-out.
    async fn fake(answer: Json) -> (String, Seen) {
        let seen: Seen = Arc::default();
        let second = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let there = format!("http://{}", second.local_addr().unwrap());
        let app = Router::new()
            .route(
                SIGN_IN,
                route({
                    let answer = answer.clone();
                    move |State(seen): State<Seen>, headers: HeaderMap, body: String| async move {
                        record(&seen, SIGN_IN, &headers, &body);
                        axum::Json(answer)
                    }
                }),
            )
            .route(
                DEVICES,
                route(
                    |State(seen): State<Seen>, headers: HeaderMap, body: String| async move {
                        record(&seen, DEVICES, &headers, &body);
                        axum::Json(json!({ "apiStatus": 0, "data": [
                            { "uuid": "00000000000000000000000000000001", "devName": "Lampe", "deviceType": "mss315", "onlineStatus": 1 },
                            { "uuid": "00000000000000000000000000000002", "devName": "", "deviceType": "mss310", "onlineStatus": 2 },
                            { "devName": "sans uuid" }
                        ] }))
                    },
                ),
            )
            .route(
                SIGN_OUT,
                route(
                    |State(seen): State<Seen>, headers: HeaderMap, body: String| async move {
                        record(&seen, SIGN_OUT, &headers, &body);
                        axum::Json(json!({ "apiStatus": 0, "data": {} }))
                    },
                ),
            )
            .with_state(seen.clone());
        tokio::spawn(async move { axum::serve(second, app).await.unwrap() });
        let first = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let here = format!("http://{}", first.local_addr().unwrap());
        let redirect = Router::new().route(
            SIGN_IN,
            route(move || async move {
                axum::Json(json!({ "apiStatus": REDIRECTED, "data": { "domain": there } }))
            }),
        );
        tokio::spawn(async move { axum::serve(first, redirect).await.unwrap() });
        (here, seen)
    }

    fn account(code: Option<&str>) -> Account {
        Account {
            email: " user@example.com ".into(),
            password: "fictional".into(),
            code: code.map(Into::into),
        }
    }

    #[tokio::test]
    async fn the_key_is_read_then_the_session_closed() {
        let (server, seen) = fake(json!({
            "apiStatus": 0,
            "data": { "key": "fictional-account-key", "token": "fictional-token", "userid": "1", "email": "user@example.com" }
        }))
        .await;
        let out = key_from(&server, &account(None)).await.unwrap();
        assert_eq!(
            out,
            json!({ "key": "fictional-account-key", "devices": [
                { "uuid": "00000000000000000000000000000001", "name": "Lampe", "model": "mss315", "online": true },
                { "uuid": "00000000000000000000000000000002", "name": "00000000000000000000000000000002", "model": "mss310", "online": false }
            ] })
        );
        let seen = seen.lock().unwrap();
        assert_eq!(seen.len(), 3, "{seen:?}");
        let (path, params, auth) = &seen[0];
        assert_eq!(path, SIGN_IN);
        assert_eq!(params["email"], "user@example.com");
        // md5("fictional"), never the password itself.
        assert_eq!(params["password"], hex(&Md5::digest(b"fictional")));
        assert_eq!(params["accountCountryCode"], "eu");
        assert!(params.get("mfaCode").is_none());
        assert!(auth.is_none());
        assert_eq!(seen[1].0, DEVICES);
        assert_eq!(seen[1].2.as_deref(), Some("Basic fictional-token"));
        assert_eq!(seen[2].0, SIGN_OUT);
        assert_eq!(seen[2].2.as_deref(), Some("Basic fictional-token"));
    }

    #[tokio::test]
    async fn a_refusal_is_given_back_with_its_status() {
        let (server, seen) =
            fake(json!({ "apiStatus": 1033, "info": "MFA Code required", "data": {} })).await;
        let out = key_from(&server, &account(Some(" 123456 "))).await.unwrap();
        assert_eq!(out, json!({ "status": 1033, "info": "MFA Code required" }));
        let seen = seen.lock().unwrap();
        assert_eq!(seen.len(), 1, "no sign-out without a session");
        assert_eq!(seen[0].1["mfaCode"], "123456");
    }
}
