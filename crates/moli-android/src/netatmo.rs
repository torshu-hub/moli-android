//! The connection to Netatmo from the app (DECISIONS 6), as Home Assistant
//! makes it: the person's own Netatmo application (`client_id`,
//! `client_secret`), a page of Netatmo where they accept, and the
//! `refresh_token` that comes back, with nothing to paste. The driver
//! (`moli-netatmo`) renews it from then on.
//!
//! Home Assistant's redirection goes to a page it hosts; here it goes to
//! the app itself, which listens for one visit on the tablet
//! (`http://localhost:8791/netatmo`, to declare in the Netatmo application).
//! Two steps, so that the screen can open the browser between them: [`begin`]
//! returns the address to open, [`wait`] hears the redirection, trades the
//! code for the tokens and puts them in the vault. No secret is returned
//! to the screen or written to the journal.

use std::fmt::Write as _;
use std::io::{ErrorKind, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::Path;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use anyhow::Context as _;
use moli_net::Method;
use moli_runtime::MasterKey;
use ring::rand::{SecureRandom as _, SystemRandom};
use serde_json::{Value as Json, json};

use crate::setup;

const PORT: u16 = 8791;
const REDIRECT: &str = "http://localhost:8791/netatmo";
const AUTHORIZE: &str = "https://api.netatmo.com/oauth2/authorize";
const HOST: &str = "api.netatmo.com";
/// What the driver reads and commands: heating, stations, air monitors.
const SCOPE: &str = "read_thermostat write_thermostat read_station read_homecoach";
/// How long the person has to accept on Netatmo's page.
const WAIT: Duration = Duration::from_secs(300);
const LIMIT: Duration = Duration::from_secs(15);
const MAX_BODY: usize = 64 * 1024;

/// What the screen sends: the instance, and the application's identifiers
/// when typed (else the vault's).
#[derive(serde::Deserialize)]
pub struct Connect {
    pub instance: String,
    #[serde(default)]
    pub client_id: String,
    #[serde(default)]
    pub client_secret: String,
}

impl std::fmt::Debug for Connect {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Connect")
            .field("instance", &self.instance)
            .finish_non_exhaustive()
    }
}

struct Pending {
    listener: TcpListener,
    client_id: String,
    client_secret: String,
    state: String,
    generation: u64,
}

static PENDING: Mutex<Option<Pending>> = Mutex::new(None);
/// Bumped by each [`begin`]: a [`wait`] of an older one gives up and frees the port.
static GENERATION: AtomicU64 = AtomicU64::new(0);

fn encode(value: &str) -> String {
    let mut out = String::new();
    for b in value.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(char::from(b));
        } else {
            let _ = write!(out, "%{b:02X}");
        }
    }
    out
}

fn decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let hex = (bytes[i] == b'%')
            .then(|| bytes.get(i + 1..i + 3))
            .flatten()
            .and_then(|h| std::str::from_utf8(h).ok())
            .and_then(|h| u8::from_str_radix(h, 16).ok());
        if let Some(b) = hex {
            out.push(b);
            i += 3;
        } else {
            out.push(if bytes[i] == b'+' { b' ' } else { bytes[i] });
            i += 1;
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// The identifier typed, else the vault's.
fn resolved(
    config: &Path,
    key: &MasterKey,
    instance: &str,
    name: &str,
    typed: &str,
) -> anyhow::Result<String> {
    let typed = typed.trim();
    if !typed.is_empty() {
        return Ok(typed.to_owned());
    }
    setup::held(config, key, instance, name)?
        .with_context(|| format!("{name} is missing: type it, then connect"))
}

fn authorize_url(client_id: &str, state: &str) -> String {
    format!(
        "{AUTHORIZE}?client_id={}&redirect_uri={}&scope={}&state={}&response_type=code",
        encode(client_id),
        encode(REDIRECT),
        encode(SCOPE),
        encode(state)
    )
}

/// Opens the listening: `{"url": …, "redirect_uri": …}`, the address for the
/// browser and the one to declare in the Netatmo application.
pub fn begin(config: &Path, key: &MasterKey, connect: &Connect) -> anyhow::Result<Json> {
    let client_id = resolved(
        config,
        key,
        &connect.instance,
        "client_id",
        &connect.client_id,
    )?;
    let client_secret = resolved(
        config,
        key,
        &connect.instance,
        "client_secret",
        &connect.client_secret,
    )?;
    let generation = GENERATION.fetch_add(1, Ordering::SeqCst) + 1;
    *PENDING
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
    let listener = bind()?;
    let mut raw = [0u8; 16];
    SystemRandom::new()
        .fill(&mut raw)
        .map_err(|_| anyhow::anyhow!("no randomness"))?;
    let state = raw.iter().fold(String::new(), |mut s, b| {
        let _ = write!(s, "{b:02x}");
        s
    });
    tracing::info!("netatmo: waiting for the redirection on {REDIRECT}");
    let url = authorize_url(&client_id, &state);
    *PENDING
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(Pending {
        listener,
        client_id,
        client_secret,
        state,
        generation,
    });
    Ok(json!({ "url": url, "redirect_uri": REDIRECT }))
}

/// The port may be held a moment by an earlier wait that is giving up.
fn bind() -> anyhow::Result<TcpListener> {
    let mut last = None;
    for _ in 0..20 {
        match TcpListener::bind(("127.0.0.1", PORT)) {
            Ok(listener) => {
                listener.set_nonblocking(true)?;
                return Ok(listener);
            }
            Err(e) => last = Some(e),
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    Err(last.map_or_else(
        || anyhow::anyhow!("cannot listen"),
        |e| anyhow::anyhow!("cannot listen on port {PORT}: {e}"),
    ))
}

/// What came to the listening port.
#[derive(Debug, PartialEq, Eq)]
enum Heard {
    Code(String),
    /// Netatmo's `error` (`access_denied`…).
    Denied(String),
    Timeout,
    Cancelled,
}

/// What one visit means.
#[derive(Debug, PartialEq, Eq)]
enum Visit {
    Code(String),
    Denied(String),
    /// Not for us: another path, another `state` (an old tab).
    Ignored,
}

/// `GET /netatmo?code=…&state=… HTTP/1.1`.
fn visit(request_line: &str, state: &str) -> Visit {
    let mut parts = request_line.split_whitespace();
    let (Some("GET"), Some(target)) = (parts.next(), parts.next()) else {
        return Visit::Ignored;
    };
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    if path != "/netatmo" {
        return Visit::Ignored;
    }
    let value = |name: &str| {
        query
            .split('&')
            .filter_map(|pair| pair.split_once('='))
            .find(|(k, _)| *k == name)
            .map(|(_, v)| decode(v))
    };
    if value("state").as_deref() != Some(state) {
        return Visit::Ignored;
    }
    if let Some(error) = value("error") {
        return Visit::Denied(error);
    }
    value("code").map_or(Visit::Ignored, Visit::Code)
}

fn page(stream: &mut TcpStream, status: &str, text: &str) {
    let body = format!(
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>Moli</title><body style=\"font:18px sans-serif;padding:2em\"><p>{text}</p>"
    );
    let _ = write!(
        stream,
        "HTTP/1.1 {status}\r\ncontent-type: text/html; charset=utf-8\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
        body.len()
    );
}

/// One visit: its first line, answered.
fn serve(mut stream: TcpStream, state: &str) -> Visit {
    let _ = stream.set_nonblocking(false);
    let _ = stream.set_read_timeout(Some(Duration::from_secs(3)));
    let mut head = Vec::new();
    let mut chunk = [0u8; 1024];
    while head.len() < 8192 && !head.windows(2).any(|w| w == b"\r\n") {
        match stream.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(n) => head.extend_from_slice(&chunk[..n]),
        }
    }
    let head = String::from_utf8_lossy(&head).into_owned();
    let line = head.lines().next().unwrap_or("");
    let seen = visit(line, state);
    match &seen {
        Visit::Code(_) => page(
            &mut stream,
            "200 OK",
            "Netatmo est connecté : vous pouvez revenir à Moli.<br>Netatmo is connected: you can return to Moli.",
        ),
        Visit::Denied(_) => page(
            &mut stream,
            "200 OK",
            "Netatmo a refusé l'accès. Revenez à Moli.<br>Netatmo refused access. Return to Moli.",
        ),
        Visit::Ignored => page(&mut stream, "404 Not Found", "Moli"),
    }
    seen
}

fn listen(
    listener: &TcpListener,
    state: &str,
    cancelled: impl Fn() -> bool,
    wait: Duration,
) -> anyhow::Result<Heard> {
    let deadline = Instant::now() + wait;
    loop {
        if cancelled() {
            return Ok(Heard::Cancelled);
        }
        if Instant::now() >= deadline {
            return Ok(Heard::Timeout);
        }
        match listener.accept() {
            Ok((stream, _)) => match serve(stream, state) {
                Visit::Code(code) => return Ok(Heard::Code(code)),
                Visit::Denied(error) => return Ok(Heard::Denied(error)),
                Visit::Ignored => {}
            },
            Err(e) if e.kind() == ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(100));
            }
            Err(e) => return Err(e.into()),
        }
    }
}

/// The tokens for the code: `Ok(Err(why))` is Netatmo's refusal (a wrong
/// `client_secret`, a code already used), told to the person.
async fn exchange(
    server: (&str, u16, bool),
    client_id: &str,
    client_secret: &str,
    code: &str,
) -> anyhow::Result<Result<String, String>> {
    let form = [
        ("grant_type", "authorization_code"),
        ("client_id", client_id),
        ("client_secret", client_secret),
        ("code", code),
        ("redirect_uri", REDIRECT),
        ("scope", SCOPE),
    ]
    .iter()
    .map(|(k, v)| format!("{k}={}", encode(v)))
    .collect::<Vec<_>>()
    .join("&");
    let builder = http::Request::builder()
        .method(Method::POST)
        .uri("/oauth2/token");
    let request = moli_net::text_body(
        builder,
        "application/x-www-form-urlencoded;charset=UTF-8",
        form,
    )?;
    let (status, answer) =
        moli_net::web(server.0, server.1, server.2, request, LIMIT, MAX_BODY).await?;
    let answer: Json = serde_json::from_slice(&answer).unwrap_or(Json::Null);
    if !status.is_success() {
        let why = answer["error_description"]
            .as_str()
            .or_else(|| answer["error"].as_str())
            .map_or_else(|| format!("HTTP {}", status.as_u16()), str::to_owned);
        return Ok(Err(why));
    }
    match (
        answer["access_token"].as_str(),
        answer["refresh_token"].as_str(),
    ) {
        (Some(_), Some(refresh)) if !refresh.is_empty() => Ok(Ok(refresh.to_owned())),
        _ => Ok(Err("no refresh_token in the answer".to_owned())),
    }
}

/// Hears the redirection, trades the code, keeps the tokens in the vault:
/// `{"status": "connected"}`, or `denied` / `refused` / `timeout` /
/// `cancelled` with an `info` (never a secret).
pub fn wait(config: &Path, key: &MasterKey, instance: &str) -> anyhow::Result<Json> {
    let pending = PENDING
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .take()
        .context("no connection started")?;
    let generation = pending.generation;
    let heard = listen(
        &pending.listener,
        &pending.state,
        || GENERATION.load(Ordering::SeqCst) != generation,
        WAIT,
    )?;
    drop(pending.listener);
    let code = match heard {
        Heard::Code(code) => code,
        Heard::Denied(error) => {
            tracing::warn!("netatmo: the person refused on Netatmo's page ({error})");
            return Ok(json!({ "status": "denied", "info": error }));
        }
        Heard::Timeout => {
            tracing::warn!("netatmo: nobody answered on Netatmo's page in time");
            return Ok(json!({ "status": "timeout" }));
        }
        Heard::Cancelled => return Ok(json!({ "status": "cancelled" })),
    };
    tracing::info!("netatmo: redirection heard, trading the code for the tokens");
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    let tokens = runtime.block_on(exchange(
        (HOST, 443, true),
        &pending.client_id,
        &pending.client_secret,
        &code,
    ))?;
    let refresh = match tokens {
        Ok(refresh) => refresh,
        Err(why) => {
            tracing::warn!("netatmo: the code was refused ({why})");
            return Ok(json!({ "status": "refused", "info": why }));
        }
    };
    let values = [
        ("client_id".to_owned(), pending.client_id),
        ("client_secret".to_owned(), pending.client_secret),
        ("refresh_token".to_owned(), refresh),
    ]
    .into_iter()
    .collect();
    setup::store(config, key, instance, &values)?;
    tracing::info!("netatmo: connected, the refresh_token is in the vault");
    Ok(json!({ "status": "connected" }))
}

/// Gives up a connection in progress (the person left the screen).
pub fn cancel() {
    GENERATION.fetch_add(1, Ordering::SeqCst);
    *PENDING
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
}

#[cfg(test)]
mod tests {
    use super::*;

    use axum::Router;
    use axum::routing::post as route;

    #[test]
    fn the_address_for_netatmo_names_the_scopes_and_the_return() {
        let url = authorize_url("my id", "abc");
        assert!(url.starts_with("https://api.netatmo.com/oauth2/authorize?client_id=my%20id&"));
        assert!(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8791%2Fnetatmo"));
        assert!(
            url.contains(
                "scope=read_thermostat%20write_thermostat%20read_station%20read_homecoach"
            )
        );
        assert!(url.contains("state=abc") && url.contains("response_type=code"));
    }

    #[test]
    fn a_visit_gives_the_code_the_denial_or_nothing() {
        let line = |q: &str| format!("GET /netatmo?{q} HTTP/1.1");
        assert_eq!(
            visit(&line("code=5e%7Cab&state=s1"), "s1"),
            Visit::Code("5e|ab".into())
        );
        assert_eq!(
            visit(&line("error=access_denied&state=s1"), "s1"),
            Visit::Denied("access_denied".into())
        );
        // Another state is an old tab or a stranger: ignored.
        assert_eq!(visit(&line("code=x&state=other"), "s1"), Visit::Ignored);
        assert_eq!(visit(&line("code=x"), "s1"), Visit::Ignored);
        assert_eq!(visit("GET /favicon.ico HTTP/1.1", "s1"), Visit::Ignored);
        assert_eq!(
            visit("POST /netatmo?code=x&state=s1 HTTP/1.1", "s1"),
            Visit::Ignored
        );
        assert_eq!(visit("", "s1"), Visit::Ignored);
    }

    #[test]
    fn percent_signs_are_read_and_a_lone_one_is_kept() {
        assert_eq!(decode("a%7Cb%20c+d"), "a|b c d");
        assert_eq!(decode("100%"), "100%");
        assert_eq!(decode("%zz"), "%zz");
    }

    fn open() -> (TcpListener, u16) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let port = listener.local_addr().unwrap().port();
        (listener, port)
    }

    fn get(port: u16, target: &str) -> String {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
        write!(stream, "GET {target} HTTP/1.1\r\nhost: localhost\r\n\r\n").unwrap();
        let mut answer = String::new();
        stream.read_to_string(&mut answer).unwrap();
        answer
    }

    #[test]
    fn the_listener_hears_the_redirection_and_ignores_the_rest() {
        let (listener, port) = open();
        let browser = std::thread::spawn(move || {
            assert!(get(port, "/favicon.ico").starts_with("HTTP/1.1 404"));
            assert!(get(port, "/netatmo?code=old&state=stale").starts_with("HTTP/1.1 404"));
            get(port, "/netatmo?code=the-code&state=good")
        });
        let heard = listen(&listener, "good", || false, Duration::from_secs(10)).unwrap();
        assert_eq!(heard, Heard::Code("the-code".into()));
        let answer = browser.join().unwrap();
        assert!(answer.starts_with("HTTP/1.1 200 OK") && answer.contains("Netatmo"));
    }

    #[test]
    fn a_refusal_on_the_page_is_heard() {
        let (listener, port) = open();
        let browser = std::thread::spawn(move || get(port, "/netatmo?error=access_denied&state=s"));
        let heard = listen(&listener, "s", || false, Duration::from_secs(10)).unwrap();
        assert_eq!(heard, Heard::Denied("access_denied".into()));
        browser.join().unwrap();
    }

    #[test]
    fn nobody_coming_ends_the_wait_and_a_new_start_cancels_it() {
        let (listener, _) = open();
        assert_eq!(
            listen(&listener, "s", || false, Duration::from_millis(150)).unwrap(),
            Heard::Timeout
        );
        assert_eq!(
            listen(&listener, "s", || true, Duration::from_secs(10)).unwrap(),
            Heard::Cancelled
        );
    }

    /// A fake Netatmo token endpoint answering `status` and `answer`.
    async fn fake(status: u16, answer: Json, seen: std::sync::Arc<Mutex<Vec<String>>>) -> u16 {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let app = Router::new().route(
            "/oauth2/token",
            route(move |body: String| {
                let answer = answer.clone();
                let seen = seen.clone();
                async move {
                    seen.lock().unwrap().push(body);
                    (
                        axum::http::StatusCode::from_u16(status).unwrap(),
                        axum::Json(answer),
                    )
                }
            }),
        );
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        port
    }

    #[tokio::test]
    async fn the_code_is_traded_for_the_refresh_token() {
        let seen = std::sync::Arc::default();
        let port = fake(
            200,
            json!({"access_token": "a", "refresh_token": "r-1", "expires_in": 10800}),
            std::sync::Arc::clone(&seen),
        )
        .await;
        let got = exchange(("127.0.0.1", port, false), "id", "secret", "co|de")
            .await
            .unwrap();
        assert_eq!(got, Ok("r-1".to_owned()));
        let body = seen.lock().unwrap()[0].clone();
        for part in [
            "grant_type=authorization_code",
            "client_id=id",
            "client_secret=secret",
            "code=co%7Cde",
            "redirect_uri=http%3A%2F%2Flocalhost%3A8791%2Fnetatmo",
        ] {
            assert!(body.contains(part), "{part} in {body}");
        }
    }

    #[tokio::test]
    async fn a_refused_code_is_told_not_thrown() {
        let port = fake(
            400,
            json!({"error": "invalid_client"}),
            std::sync::Arc::default(),
        )
        .await;
        let got = exchange(("127.0.0.1", port, false), "id", "wrong", "c")
            .await
            .unwrap();
        assert_eq!(got, Err("invalid_client".to_owned()));
        let port = fake(200, json!({"access_token": "a"}), std::sync::Arc::default()).await;
        let got = exchange(("127.0.0.1", port, false), "id", "s", "c")
            .await
            .unwrap();
        assert!(got.is_err());
    }
}
