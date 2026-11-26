//! The Meross plugs of the account, found on the home network and added to
//! the house with their names from the Meross app: no address to look up,
//! nothing to type.
//!
//! Finding: the other addresses of the tablet's /24 are knocked on port 80,
//! briefly; whoever answers is asked who it is by the official recognition
//! of « Ajouter un appareil » (`moli_profile::recognize`), with the
//! account's key: a Meross plug gives its uuid, the one the account lists.
//!
//! Adding: through the official API of « Ajouter un appareil », as the
//! dashboard does it (a session opened with the house's code, which the app
//! holds): the plugs land where that page puts them (`devices-added.json`,
//! the key in the vault), portable to another machine.
//!
//! The house: a plug Moli runs is the device `<instance>:<uuid>` of
//! `/api/devices` (the profile's identity is the plug's uuid), wherever it
//! was added from. Renaming goes through the dashboard's own call
//! (`PUT /api/labels/{id}`).

use std::collections::BTreeMap;
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::time::Duration;

use anyhow::{Context as _, bail};
use moli_net::Method;
use moli_profile::Recognition;
use serde::Deserialize;
use serde_json::{Value as Json, json};
use tokio::net::TcpStream;
use tokio::task::JoinSet;

/// Long enough for a plug on the home Wi-Fi to accept a connection.
const KNOCK: Duration = Duration::from_millis(600);
/// Addresses knocked on at once.
const KNOCK_AT_ONCE: usize = 64;
/// Addresses asked who they are at once (each asks a few messages).
const ASK_AT_ONCE: usize = 4;
/// One addition waits for the plug to answer, to give it its name (20 s in
/// Moli), one probe asks a few messages (45 s at most in Moli).
const MOLI_WAIT: Duration = Duration::from_secs(60);

/// « Find these plugs »: the account's key, the uuids it lists, and the
/// tablet's address on the home network (the app reads it from the Wi-Fi:
/// with a VPN on, the default route is not the home's).
#[derive(Deserialize)]
pub struct Find {
    pub key: String,
    pub uuids: Vec<String>,
    #[serde(default)]
    pub own: Option<Ipv4Addr>,
}

impl std::fmt::Debug for Find {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Find")
            .field("uuids", &self.uuids)
            .finish_non_exhaustive()
    }
}

/// Where each plug answers: `{uuid: address}`, only those found.
pub fn find(request: &Find) -> anyhow::Result<Json> {
    let network = home_network(request.own)?;
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    let found = runtime.block_on(find_in(&network, None, &request.key, &request.uuids));
    Ok(json!(found))
}

/// The other host addresses of the tablet's /24, on a private network:
/// around `own`, else the default route's address.
pub(crate) fn home_network(own: Option<Ipv4Addr>) -> anyhow::Result<Vec<Ipv4Addr>> {
    let own = if let Some(own) = own {
        own
    } else {
        // No packet leaves: a UDP « connection » only picks the outgoing address.
        let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))?;
        socket.connect((Ipv4Addr::new(192, 0, 2, 1), 9))?;
        let SocketAddr::V4(own) = socket.local_addr()? else {
            bail!("no IPv4 address");
        };
        *own.ip()
    };
    if !own.is_private() {
        bail!("not on a home network");
    }
    let [a, b, c, _] = own.octets();
    Ok((1..=254)
        .map(|d| Ipv4Addr::new(a, b, c, d))
        .filter(|ip| *ip != own)
        .collect())
}

/// `port`: tests only (their fake plug listens elsewhere than port 80).
async fn find_in(
    network: &[Ipv4Addr],
    port: Option<u16>,
    key: &str,
    uuids: &[String],
) -> BTreeMap<String, String> {
    let knocked = port.unwrap_or(80);
    let mut open = Vec::new();
    for chunk in network.chunks(KNOCK_AT_ONCE) {
        let mut knocks = JoinSet::new();
        for &ip in chunk {
            knocks.spawn(async move {
                let reached = tokio::time::timeout(KNOCK, TcpStream::connect((ip, knocked))).await;
                matches!(reached, Ok(Ok(_))).then_some(ip)
            });
        }
        open.extend(knocks.join_all().await.into_iter().flatten());
    }
    let wanted: Vec<String> = uuids.iter().map(|u| u.to_ascii_lowercase()).collect();
    let keys = [key.to_owned()];
    let mut found = BTreeMap::new();
    for chunk in open.chunks(ASK_AT_ONCE) {
        let mut asks = JoinSet::new();
        for &ip in chunk {
            let keys = keys.clone();
            asks.spawn(async move { (ip, moli_profile::recognize(ip, port, &keys).await) });
        }
        for (ip, recognition) in asks.join_all().await {
            if let Recognition::Recognized(device) = recognition {
                let uuid = device.identity.to_ascii_lowercase();
                if wanted.contains(&uuid) {
                    found.insert(uuid, ip.to_string());
                }
            }
        }
        if found.len() == wanted.len() {
            break;
        }
    }
    found
}

/// « Which of these plugs does the house run? »: where Moli listens, the
/// house's code, the account's uuids.
#[derive(Deserialize)]
pub struct House {
    pub port: u16,
    pub pin: String,
    pub uuids: Vec<String>,
}

impl std::fmt::Debug for House {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("House")
            .field("uuids", &self.uuids)
            .finish_non_exhaustive()
    }
}

/// `{uuid: {"id": device, "name": its name in Moli}}`, only those the house runs.
pub fn house(request: &House) -> anyhow::Result<Json> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    runtime.block_on(async {
        let moli = Moli::open(request.port, &request.pin).await?;
        let devices = moli.call(Method::GET, "/api/devices", &Json::Null).await;
        moli.close().await;
        let (status, snapshot) = devices?;
        anyhow::ensure!(status == 200, "Moli: HTTP {status}");
        Ok(in_house(&snapshot, &request.uuids))
    })
}

/// The devices of `/api/devices` whose identity is one of `uuids`, with the
/// name the dashboard shows (the family's label, else the device's own).
fn in_house(snapshot: &Json, uuids: &[String]) -> Json {
    let mut out = serde_json::Map::new();
    for device in snapshot["devices"].as_array().into_iter().flatten() {
        let Some(id) = device["id"].as_str() else {
            continue;
        };
        let Some((_, native)) = id.split_once(':') else {
            continue;
        };
        let Some(uuid) = uuids.iter().find(|u| u.eq_ignore_ascii_case(native)) else {
            continue;
        };
        let name = device["label"]["name"]
            .as_str()
            .or_else(|| device["native_name"].as_str())
            .unwrap_or(id);
        out.insert(uuid.clone(), json!({ "id": id, "name": name }));
    }
    Json::Object(out)
}

/// « Rename these devices »: where Moli listens, the house's code, and
/// each device's id and new name.
#[derive(Deserialize)]
pub struct Rename {
    pub port: u16,
    pub pin: String,
    pub devices: Vec<Named>,
}

#[derive(Debug, Deserialize)]
pub struct Named {
    pub id: String,
    pub name: String,
}

impl std::fmt::Debug for Rename {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Rename")
            .field("devices", &self.devices)
            .finish_non_exhaustive()
    }
}

/// What became of each: `{id: "renamed" | <Moli's error>}`.
pub fn rename(request: &Rename) -> anyhow::Result<Json> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    runtime.block_on(rename_in(request))
}

async fn rename_in(request: &Rename) -> anyhow::Result<Json> {
    let moli = Moli::open(request.port, &request.pin).await?;
    let mut outcomes = serde_json::Map::new();
    for device in &request.devices {
        // The id goes in the path: only what Moli's ids are made of.
        let outcome = if device
            .id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, ':' | '-' | '_' | '.'))
        {
            let path = format!("/api/labels/{}", device.id);
            match moli
                .call(Method::PUT, &path, &json!({ "name": device.name }))
                .await
            {
                Ok((200, _)) => json!("renamed"),
                Ok((_, answer)) => json!(answer["error"].as_str().unwrap_or("refused")),
                Err(e) => json!(format!("{e:#}")),
            }
        } else {
            json!("invalid")
        };
        outcomes.insert(device.id.clone(), outcome);
    }
    moli.close().await;
    Ok(Json::Object(outcomes))
}

/// « Add these plugs »: where Moli listens, the house's code, the account's
/// key, and each plug's address and name.
#[derive(Deserialize)]
pub struct Add {
    pub port: u16,
    pub pin: String,
    pub key: String,
    pub plugs: Vec<Plug>,
}

#[derive(Debug, Deserialize)]
pub struct Plug {
    pub host: String,
    pub name: String,
}

impl std::fmt::Debug for Add {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Add")
            .field("plugs", &self.plugs)
            .finish_non_exhaustive()
    }
}

/// What became of each: `{host: {"outcome": "added" | "already" | <Moli's
/// error code>, "instance": …}}`.
pub fn add(request: &Add) -> anyhow::Result<Json> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    runtime.block_on(add_to(request))
}

async fn add_to(request: &Add) -> anyhow::Result<Json> {
    let moli = Moli::open(request.port, &request.pin).await?;
    let mut outcomes = serde_json::Map::new();
    for plug in &request.plugs {
        outcomes.insert(plug.host.clone(), moli.add(plug, &request.key).await);
    }
    moli.close().await;
    Ok(Json::Object(outcomes))
}

/// Moli refuses a request without a `Host` it knows (its guard against DNS
/// rebinding): this very machine's.
fn host(port: u16) -> String {
    format!("127.0.0.1:{port}")
}

/// The house's own API, as the dashboard calls it: a human session.
struct Moli {
    port: u16,
    cookie: String,
}

impl Moli {
    async fn open(port: u16, pin: &str) -> anyhow::Result<Self> {
        let request = moli_net::json_body(
            http::Request::builder()
                .method(Method::POST)
                .uri("/api/session")
                .header(http::header::HOST, host(port)),
            &json!({ "pin": pin }),
        )?;
        let (status, headers, _) = moli_net::plain_with_headers("127.0.0.1", port, request).await?;
        anyhow::ensure!(
            status.is_success(),
            "Moli refused the house's code: HTTP {}",
            status.as_u16()
        );
        let cookie = headers
            .get_all(http::header::SET_COOKIE)
            .iter()
            .filter_map(|v| v.to_str().ok())
            .find_map(|v| {
                v.split(';')
                    .next()
                    .filter(|kv| kv.starts_with("moli_session="))
            })
            .context("Moli opened no session")?
            .to_owned();
        Ok(Self { port, cookie })
    }

    async fn call(&self, method: Method, path: &str, body: &Json) -> anyhow::Result<(u16, Json)> {
        let builder = http::Request::builder()
            .method(method)
            .uri(path)
            .header(http::header::HOST, host(self.port))
            .header(http::header::COOKIE, &self.cookie)
            .header("x-moli-origin", "ui");
        let request = if body.is_null() {
            moli_net::empty(builder)?
        } else {
            moli_net::json_body(builder, body)?
        };
        let (status, answer) =
            moli_net::plain_within("127.0.0.1", self.port, request, MOLI_WAIT).await?;
        Ok((
            status.as_u16(),
            serde_json::from_slice(&answer).unwrap_or(Json::Null),
        ))
    }

    /// « Who is there? », then « add it » with its name: the dashboard's two steps.
    async fn add(&self, plug: &Plug, key: &str) -> Json {
        let failed =
            |e: anyhow::Error| json!({ "outcome": "unreachable", "detail": format!("{e:#}") });
        let refused =
            |answer: &Json| json!({ "outcome": answer["error"].as_str().unwrap_or("refused") });
        let probe = json!({ "host": plug.host, "key": key });
        let preview = match self
            .call(Method::POST, "/api/integrations/devices/probe", &probe)
            .await
        {
            Ok((200, preview)) => preview,
            Ok((_, answer)) => return refused(&answer),
            Err(e) => return failed(e),
        };
        if let Some(instance) = preview["existing_instance"].as_str() {
            return json!({ "outcome": "already", "instance": instance });
        }
        if preview["status"] != "recognized" {
            return json!({ "outcome": preview["status"].as_str().unwrap_or("unknown") });
        }
        let install = json!({
            "host": plug.host,
            "identity": preview["identity"],
            "name": plug.name,
            "key": key,
        });
        match self
            .call(Method::POST, "/api/integrations/devices", &install)
            .await
        {
            Ok((200, added)) => json!({ "outcome": "added", "instance": added["instance"] }),
            Ok((_, answer)) => refused(&answer),
            Err(e) => failed(e),
        }
    }

    async fn close(self) {
        let _ = self.call(Method::DELETE, "/api/session", &json!({})).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::sync::{Arc, Mutex};

    use axum::Router;
    use axum::extract::State;
    use axum::http::{HeaderMap, StatusCode};
    use axum::routing::post;
    use md5::{Digest as _, Md5};
    use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};

    const KEY: &str = "fictional-account-key";
    const UUID: &str = "00000000000000000000000000000001";

    /// A Meross plug as the package's fixtures describe it: it answers a
    /// message its key signed, `ERROR` to any other.
    async fn plug() -> u16 {
        let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
            .await
            .unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            while let Ok((mut tcp, _)) = listener.accept().await {
                let mut request = Vec::new();
                let mut buf = [0u8; 2048];
                let body = loop {
                    match tcp.read(&mut buf).await {
                        Ok(0) | Err(_) => break None,
                        Ok(n) => request.extend_from_slice(&buf[..n]),
                    }
                    let Some(end) = request.windows(4).position(|w| w == b"\r\n\r\n") else {
                        continue;
                    };
                    let head = String::from_utf8_lossy(&request[..end]).to_ascii_lowercase();
                    let length: usize = head
                        .lines()
                        .find_map(|l| l.strip_prefix("content-length:"))
                        .map_or(0, |l| l.trim().parse().unwrap());
                    if request.len() >= end + 4 + length {
                        break Some(request[end + 4..end + 4 + length].to_vec());
                    }
                };
                let Some(body) = body else { continue };
                let message: Json = serde_json::from_slice(&body).unwrap();
                let header = &message["header"];
                let signed = Md5::digest(
                    format!(
                        "{}{KEY}{}",
                        header["messageId"].as_str().unwrap(),
                        header["timestamp"].as_u64().unwrap()
                    )
                    .as_bytes(),
                );
                let signed = crate::meross::hex(&signed);
                let answer = if header["sign"].as_str() != Some(signed.as_str()) {
                    json!({"header": {"method": "ERROR"}, "payload": {"error": {"code": 5001}}})
                } else if header["namespace"] == "Appliance.System.All" {
                    let path = format!(
                        "{}/../../integrations/meross-mss310/fixtures/all.json",
                        env!("CARGO_MANIFEST_DIR")
                    );
                    let mut all: Json =
                        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
                    all["payload"]["all"]["system"]["hardware"]["type"] = "mss315".into();
                    all["payload"]["all"]["system"]["hardware"]["uuid"] = UUID.into();
                    all
                } else {
                    json!({"header": {"method": "GETACK"}, "payload": {"ability": {}}})
                };
                let answer = answer.to_string();
                let answer = format!(
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{answer}",
                    answer.len()
                );
                let _ = tcp.write_all(answer.as_bytes()).await;
            }
        });
        port
    }

    #[tokio::test]
    async fn a_plug_of_the_account_is_found_by_its_uuid() {
        let port = plug().await;
        // A silent address beside it.
        let network = [Ipv4Addr::LOCALHOST, Ipv4Addr::new(127, 0, 0, 2)];
        let found = find_in(&network, Some(port), KEY, &[UUID.to_uppercase()]).await;
        assert_eq!(
            found,
            BTreeMap::from([(UUID.to_owned(), "127.0.0.1".to_owned())])
        );
        // Another key: nothing recognized, nothing found.
        assert!(
            find_in(&network, Some(port), "another-key", &[UUID.into()])
                .await
                .is_empty()
        );
        // A plug the account does not list is left out.
        assert!(
            find_in(&network, Some(port), KEY, &["ffff".into()])
                .await
                .is_empty()
        );
    }

    /// What the fake Moli received: (path, body, cookie, origin).
    type Seen = Arc<Mutex<Vec<(String, Json, Option<String>, Option<String>)>>>;

    fn record(seen: &Seen, path: &str, headers: &HeaderMap, body: &str) -> Json {
        let body: Json = serde_json::from_str(body).unwrap_or(Json::Null);
        let header = |name: &str| headers.get(name).map(|v| v.to_str().unwrap().to_owned());
        // Moli's guard refuses a request without a host it knows.
        assert!(
            header("host").is_some_and(|h| h.starts_with("127.0.0.1:")),
            "{path}: no host"
        );
        seen.lock().unwrap().push((
            path.to_owned(),
            body.clone(),
            header("cookie"),
            header("x-moli-origin"),
        ));
        body
    }

    /// The dashboard's API, as Moli answers it: `192.168.1.10` is a new
    /// plug, `192.168.1.11` one already in the house, `192.168.1.12` silent.
    async fn moli() -> (u16, Seen) {
        let seen: Seen = Arc::default();
        let app = Router::new()
            .route(
                "/api/session",
                post(|State(seen): State<Seen>, headers: HeaderMap, body: String| async move {
                    let body = record(&seen, "/api/session", &headers, &body);
                    if body["pin"] == "482913" {
                        ([(http::header::SET_COOKIE, "moli_session=fictional; Path=/; HttpOnly")], axum::Json(json!({ "human": true }))).into_response()
                    } else {
                        StatusCode::UNAUTHORIZED.into_response()
                    }
                })
                .delete(|State(seen): State<Seen>, headers: HeaderMap, body: String| async move {
                    record(&seen, "/api/session (delete)", &headers, &body);
                    StatusCode::NO_CONTENT
                }),
            )
            .route(
                "/api/integrations/devices/probe",
                post(|State(seen): State<Seen>, headers: HeaderMap, body: String| async move {
                    let body = record(&seen, "probe", &headers, &body);
                    let answer = match body["host"].as_str() {
                        Some("192.168.1.10") => json!({ "status": "recognized", "identity": UUID, "key": "given" }),
                        Some("192.168.1.11") => json!({ "status": "recognized", "identity": "2", "existing_instance": "meross-2" }),
                        _ => json!({ "status": "silent" }),
                    };
                    axum::Json(answer)
                }),
            )
            .route(
                "/api/integrations/devices",
                post(|State(seen): State<Seen>, headers: HeaderMap, body: String| async move {
                    record(&seen, "install", &headers, &body);
                    axum::Json(json!({ "instance": "meross-1", "device": "meross-1:1", "labelled": true }))
                }),
            )
            .route(
                "/api/devices",
                axum::routing::get(|State(seen): State<Seen>, headers: HeaderMap| async move {
                    record(&seen, "devices", &headers, "");
                    axum::Json(json!({ "devices": [
                        { "id": format!("meross-1:{UUID}"), "native_name": "Smart Plug", "label": { "name": "Salon" } },
                        { "id": "meross-2:00000000000000000000000000000002", "native_name": "Smart Plug", "label": {} },
                        { "id": "hue:1", "native_name": "Lampe", "label": {} }
                    ] }))
                }),
            )
            .route(
                "/api/labels/{id}",
                axum::routing::put(
                    |State(seen): State<Seen>, axum::extract::Path(id): axum::extract::Path<String>, headers: HeaderMap, body: String| async move {
                        let body = record(&seen, &format!("label {id}"), &headers, &body);
                        if id.starts_with("meross-") {
                            axum::Json(json!({ "name": body["name"] })).into_response()
                        } else {
                            (StatusCode::NOT_FOUND, axum::Json(json!({ "error": "unknown device" }))).into_response()
                        }
                    },
                ),
            )
            .with_state(seen.clone());
        let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
            .await
            .unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        (port, seen)
    }

    use axum::response::IntoResponse as _;

    fn request(port: u16, pin: &str) -> Add {
        Add {
            port,
            pin: pin.into(),
            key: KEY.into(),
            plugs: ["192.168.1.10", "192.168.1.11", "192.168.1.12"]
                .iter()
                .map(|host| Plug {
                    host: (*host).into(),
                    name: format!("Prise {host}"),
                })
                .collect(),
        }
    }

    #[tokio::test]
    async fn plugs_are_added_as_the_dashboard_adds_them() {
        let (port, seen) = moli().await;
        let out = add_to(&request(port, "482913")).await.unwrap();
        assert_eq!(
            out,
            json!({
                "192.168.1.10": { "outcome": "added", "instance": "meross-1" },
                "192.168.1.11": { "outcome": "already", "instance": "meross-2" },
                "192.168.1.12": { "outcome": "silent" },
            })
        );
        let seen = seen.lock().unwrap();
        let paths: Vec<&str> = seen.iter().map(|(p, ..)| p.as_str()).collect();
        assert_eq!(
            paths,
            [
                "/api/session",
                "probe",
                "install",
                "probe",
                "probe",
                "/api/session (delete)"
            ]
        );
        // Every call after the code is a human's: the session and the dashboard's origin.
        for (_, _, cookie, origin) in &seen[1..] {
            assert_eq!(cookie.as_deref(), Some("moli_session=fictional"));
            assert_eq!(origin.as_deref(), Some("ui"));
        }
        let install = &seen[2].1;
        assert_eq!(
            install,
            &json!({ "host": "192.168.1.10", "identity": UUID, "name": "Prise 192.168.1.10", "key": KEY })
        );
    }

    #[tokio::test]
    async fn a_wrong_house_code_adds_nothing() {
        let (port, seen) = moli().await;
        assert!(add_to(&request(port, "000000")).await.is_err());
        assert_eq!(seen.lock().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn the_house_s_plugs_are_read_and_renamed_as_the_dashboard_does() {
        let (port, seen) = moli().await;
        let request = House {
            port,
            pin: "482913".into(),
            uuids: vec![
                UUID.to_uppercase(),
                "00000000000000000000000000000002".into(),
                "00000000000000000000000000000003".into(),
            ],
        };
        let out = tokio::task::spawn_blocking(move || house(&request))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            out,
            json!({
                UUID.to_uppercase(): { "id": format!("meross-1:{UUID}"), "name": "Salon" },
                "00000000000000000000000000000002": { "id": "meross-2:00000000000000000000000000000002", "name": "Smart Plug" },
            })
        );
        let out = rename_in(&Rename {
            port,
            pin: "482913".into(),
            devices: vec![
                Named {
                    id: format!("meross-1:{UUID}"),
                    name: "Cuisine".into(),
                },
                Named {
                    id: "hue:9".into(),
                    name: "x".into(),
                },
                Named {
                    id: "../session".into(),
                    name: "x".into(),
                },
            ],
        })
        .await
        .unwrap();
        assert_eq!(
            out,
            json!({ format!("meross-1:{UUID}"): "renamed", "hue:9": "unknown device", "../session": "invalid" })
        );
        let seen = seen.lock().unwrap();
        let paths: Vec<&str> = seen.iter().map(|(p, ..)| p.as_str()).collect();
        assert_eq!(
            paths,
            [
                "/api/session",
                "devices",
                "/api/session (delete)",
                "/api/session",
                &format!("label meross-1:{UUID}"),
                "label hue:9",
                "/api/session (delete)"
            ]
        );
        assert_eq!(seen[4].1, json!({ "name": "Cuisine" }));
        assert_eq!(seen[4].3.as_deref(), Some("ui"));
    }
}
