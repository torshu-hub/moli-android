//! « Recherche réseau »: the devices of the home network, as a network
//! scanner shows them: address, name, maker and model where they say it.
//!
//! Who is there: every other address of the tablet's /24 is knocked on a
//! few common ports; a device that accepts *or refuses* the connection is
//! there (only a silent address is not). Devices answering SSDP are there
//! too.
//!
//! What they are, from what each says of itself, nothing guessed:
//! - its `UPnP` description (SSDP, the official `moli_net::upnp`): its name,
//!   maker and model (boxes, TVs, speakers, printers);
//! - its name on the network: the box's DNS asked for the address
//!   (reverse lookup), else the device itself over mDNS (Apple, Android,
//!   Linux devices answer for their own address);
//! - its MAC, where the system's neighbour table is readable (the official
//!   `moli_net::lan`; Android hides it from apps since Android 10).

use std::collections::BTreeMap;
use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;

use serde::Deserialize;
use serde_json::{Value as Json, json};
use tokio::net::{TcpStream, UdpSocket};
use tokio::task::JoinSet;

/// Ports knocked on: web (most devices), SSH, Apple devices' sync port,
/// cameras (RTSP), Chromecast, alternate web.
const PORTS: [u16; 7] = [80, 443, 22, 62078, 554, 8009, 8080];
/// Long enough for a device on the home Wi-Fi to accept or refuse.
const KNOCK: Duration = Duration::from_millis(800);
/// Addresses knocked on at once (each on every port of [`PORTS`]).
const KNOCK_AT_ONCE: usize = 32;
/// How long devices have to answer an SSDP search, and a name question.
const SSDP_WAIT: Duration = Duration::from_secs(3);
const NAME_WAIT: Duration = Duration::from_millis(800);
/// Names asked at once.
const NAMES_AT_ONCE: usize = 16;

/// « Who is on the network? »: the tablet's address on the home network
/// and the box's (the app reads both from the Wi-Fi; with a VPN on, the
/// default route is not the home's).
#[derive(Debug, Default, Deserialize)]
pub struct Scan {
    #[serde(default)]
    pub own: Option<Ipv4Addr>,
    #[serde(default)]
    pub gateway: Option<Ipv4Addr>,
}

/// What is known of one device.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
struct Found {
    name: Option<String>,
    maker: Option<String>,
    model: Option<String>,
    mac: Option<String>,
}

/// The devices found, by address: `[{"ip", "name", "maker", "model",
/// "mac", "role": "tablet" | "box" | null}]`, the tablet and the box
/// included.
pub fn scan(request: &Scan) -> anyhow::Result<Json> {
    let network = crate::plugs::home_network(request.own)?;
    let own = request.own.or_else(|| {
        let [a, b, c, _] = network.first()?.octets();
        // The one address of the /24 not in `network` (it lists the others).
        (1..=254)
            .map(|d| Ipv4Addr::new(a, b, c, d))
            .find(|ip| !network.contains(ip))
    });
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    let found = runtime.block_on(scan_in(&network, own, request.gateway));
    let rows: Vec<Json> = found
        .into_iter()
        .map(|(ip, f)| {
            let role = if Some(ip) == own {
                json!("tablet")
            } else if Some(ip) == request.gateway {
                json!("box")
            } else {
                Json::Null
            };
            json!({
                "ip": ip.to_string(),
                "name": f.name,
                "maker": f.maker,
                "model": f.model,
                "mac": f.mac,
                "role": role,
            })
        })
        .collect();
    Ok(Json::Array(rows))
}

async fn scan_in(
    network: &[Ipv4Addr],
    own: Option<Ipv4Addr>,
    gateway: Option<Ipv4Addr>,
) -> BTreeMap<Ipv4Addr, Found> {
    let (present, described) = tokio::join!(present(network, &PORTS), ssdp(own));
    let mut found: BTreeMap<Ipv4Addr, Found> = BTreeMap::new();
    for ip in present.into_iter().chain(own) {
        found.entry(ip).or_default();
    }
    for (ip, f) in described {
        if network.contains(&ip) || Some(ip) == own {
            found.insert(ip, f);
        }
    }
    if let Some(box_ip) = gateway {
        found.entry(box_ip).or_default();
    }
    let addresses: Vec<Ipv4Addr> = found.keys().copied().collect();
    for (ip, name) in names(&addresses, gateway, 53, 5353).await {
        if let Some(f) = found.get_mut(&ip) {
            f.name.get_or_insert(name);
        }
    }
    for (ip, mac) in moli_net::lan::neighbours().await {
        if let Some(f) = found.get_mut(&ip) {
            f.mac = Some(colons(&mac));
        }
    }
    found
}

/// `aabbccddeeff` → `aa:bb:cc:dd:ee:ff`.
fn colons(hex: &str) -> String {
    hex.as_bytes()
        .chunks(2)
        .map(|pair| String::from_utf8_lossy(pair).into_owned())
        .collect::<Vec<_>>()
        .join(":")
}

/// The addresses where something accepts or refuses a connection on one of
/// `ports`.
async fn present(network: &[Ipv4Addr], ports: &[u16]) -> Vec<Ipv4Addr> {
    let mut there = Vec::new();
    for chunk in network.chunks(KNOCK_AT_ONCE) {
        let mut knocks = JoinSet::new();
        for &ip in chunk {
            for &port in ports {
                knocks.spawn(async move { knock(ip, port).await.then_some(ip) });
            }
        }
        for ip in knocks.join_all().await.into_iter().flatten() {
            if !there.contains(&ip) {
                there.push(ip);
            }
        }
    }
    there.sort_unstable();
    there
}

/// Whether a device answers at `ip`: a connection accepted, or refused (the
/// device said no: it is there).
async fn knock(ip: Ipv4Addr, port: u16) -> bool {
    match tokio::time::timeout(KNOCK, TcpStream::connect((ip, port))).await {
        Ok(Ok(_)) => true,
        Ok(Err(e)) => e.kind() == std::io::ErrorKind::ConnectionRefused,
        Err(_) => false,
    }
}

// ---- SSDP: what UPnP devices say of themselves. ----

/// The `UPnP` devices answering a search sent from `own` (the home's
/// interface, not a VPN's), with what their description says.
async fn ssdp(own: Option<Ipv4Addr>) -> BTreeMap<Ipv4Addr, Found> {
    let mut out = BTreeMap::new();
    let Ok(socket) = UdpSocket::bind((own.unwrap_or(Ipv4Addr::UNSPECIFIED), 0)).await else {
        return out;
    };
    let search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n";
    if socket
        .send_to(search.as_bytes(), "239.255.255.250:1900")
        .await
        .is_err()
    {
        return out;
    }
    let mut locations: BTreeMap<Ipv4Addr, Vec<moli_net::upnp::Location>> = BTreeMap::new();
    let mut buf = vec![0u8; 2048];
    let deadline = tokio::time::Instant::now() + SSDP_WAIT;
    while let Ok(Ok((n, SocketAddr::V4(from)))) =
        tokio::time::timeout_at(deadline, socket.recv_from(&mut buf)).await
    {
        let ip = *from.ip();
        let answer = String::from_utf8_lossy(&buf[..n]);
        // Only a description on the device itself.
        if let Some(location) = location(&answer).filter(|l| l.host == ip.to_string()) {
            let known = locations.entry(ip).or_default();
            if !known.contains(&location) {
                known.push(location);
            }
        } else {
            locations.entry(ip).or_default();
        }
    }
    let mut reads = JoinSet::new();
    for (ip, at) in locations {
        reads.spawn(async move {
            for l in at {
                if let Ok(Ok(xml)) =
                    tokio::time::timeout(SSDP_WAIT, moli_net::upnp::fetch(&l.host, l.port, &l.path))
                        .await
                {
                    return (ip, described(&xml));
                }
            }
            (ip, Found::default())
        });
    }
    for (ip, f) in reads.join_all().await {
        out.insert(ip, f);
    }
    out
}

/// The `LOCATION` of an SSDP answer.
fn location(answer: &str) -> Option<moli_net::upnp::Location> {
    answer.lines().find_map(|line| {
        let (key, value) = line.split_once(':')?;
        key.trim()
            .eq_ignore_ascii_case("location")
            .then(|| moli_net::upnp::Location::parse(value))?
    })
}

/// Name, maker and model from a `UPnP` description (its root device).
fn described(xml: &str) -> Found {
    let field = |name: &str| {
        moli_net::upnp::tag(xml, name)
            .map(|v| moli_net::upnp::unescape(v.trim()))
            .filter(|v| !v.is_empty())
    };
    let model = match (field("modelName"), field("modelNumber")) {
        (Some(name), Some(number)) if !name.contains(&number) => Some(format!("{name} {number}")),
        (name, number) => name.or(number),
    };
    Found {
        name: field("friendlyName"),
        maker: field("manufacturer"),
        model,
        mac: None,
    }
}

// ---- Names: reverse DNS (the box), else mDNS (the device itself). ----

/// The name of each address that has one: the box's DNS first (it knows
/// what each device told it when taking its address), else the device's
/// own answer over mDNS. `dns` and `mdns`: ports (tests use others).
async fn names(
    addresses: &[Ipv4Addr],
    gateway: Option<Ipv4Addr>,
    dns: u16,
    mdns: u16,
) -> BTreeMap<Ipv4Addr, String> {
    let mut out = BTreeMap::new();
    for chunk in addresses.chunks(NAMES_AT_ONCE) {
        let mut asks = JoinSet::new();
        for &ip in chunk {
            asks.spawn(async move {
                let from_box = match gateway {
                    Some(server) => ask_name(server, dns, ip, true).await,
                    None => None,
                };
                let name = match from_box {
                    Some(name) => Some(name),
                    None => ask_name(ip, mdns, ip, false).await,
                };
                (ip, name)
            });
        }
        for (ip, name) in asks.join_all().await {
            if let Some(name) = name {
                out.insert(ip, name);
            }
        }
    }
    out
}

/// Asks `server:port` the name of `ip` (a PTR question); its first label.
async fn ask_name(server: Ipv4Addr, port: u16, ip: Ipv4Addr, recursive: bool) -> Option<String> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).await.ok()?;
    let id = u16::from(ip.octets()[3]) ^ 0x4d4f;
    socket
        .send_to(&ptr_question(id, ip, recursive), (server, port))
        .await
        .ok()?;
    let mut buf = vec![0u8; 1500];
    let (n, _) = tokio::time::timeout(NAME_WAIT, socket.recv_from(&mut buf))
        .await
        .ok()?
        .ok()?;
    ptr_answer(&buf[..n], id).map(|name| short(&name))
}

/// `d.c.b.a.in-addr.arpa`, type PTR, class IN.
fn ptr_question(id: u16, ip: Ipv4Addr, recursive: bool) -> Vec<u8> {
    let [a, b, c, d] = ip.octets();
    let mut packet = Vec::with_capacity(48);
    packet.extend_from_slice(&id.to_be_bytes());
    packet.extend_from_slice(if recursive { &[1, 0] } else { &[0, 0] });
    packet.extend_from_slice(&[0, 1, 0, 0, 0, 0, 0, 0]);
    for label in [d, c, b, a]
        .iter()
        .map(ToString::to_string)
        .chain(["in-addr".into(), "arpa".into()])
    {
        packet.push(u8::try_from(label.len()).unwrap_or(0));
        packet.extend_from_slice(label.as_bytes());
    }
    packet.extend_from_slice(&[0, 0, 12, 0, 1]);
    packet
}

/// The first PTR record's name in an answer to question `id` (mDNS
/// answers carry id 0 when the device ignores it).
fn ptr_answer(packet: &[u8], id: u16) -> Option<String> {
    let header = packet.get(..12)?;
    let got = u16::from_be_bytes([header[0], header[1]]);
    if got != id && got != 0 {
        return None;
    }
    if header[2] & 0x80 == 0 || header[3] & 0x0f != 0 {
        return None; // not an answer, or an error
    }
    let questions = u16::from_be_bytes([header[4], header[5]]);
    let answers = u16::from_be_bytes([header[6], header[7]]);
    let mut at = 12;
    for _ in 0..questions {
        at = read_name(packet, at)?.1 + 4;
    }
    for _ in 0..answers {
        at = read_name(packet, at)?.1;
        let fixed = packet.get(at..at + 10)?;
        let kind = u16::from_be_bytes([fixed[0], fixed[1]]);
        let length = usize::from(u16::from_be_bytes([fixed[8], fixed[9]]));
        at += 10;
        if kind == 12 {
            return read_name(packet, at).map(|(name, _)| name);
        }
        at += length;
    }
    None
}

/// A DNS name at `at` (compression followed, at most a few jumps): the
/// name, and where the record goes on after it.
fn read_name(packet: &[u8], mut at: usize) -> Option<(String, usize)> {
    let mut labels: Vec<String> = Vec::new();
    let mut after = None;
    for _ in 0..32 {
        let length = *packet.get(at)?;
        match length {
            0 => return Some((labels.join("."), after.unwrap_or(at + 1))),
            l if l & 0xc0 == 0xc0 => {
                let target = usize::from(u16::from_be_bytes([l & 0x3f, *packet.get(at + 1)?]));
                after.get_or_insert(at + 2);
                at = target;
            }
            l => {
                let l = usize::from(l);
                let label = packet.get(at + 1..at + 1 + l)?;
                labels.push(String::from_utf8_lossy(label).into_owned());
                at += 1 + l;
            }
        }
    }
    None
}

/// The device's own part of a name: `phone-of-x.home` → `phone-of-x`.
fn short(name: &str) -> String {
    name.trim_end_matches('.')
        .split('.')
        .next()
        .unwrap_or(name)
        .to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_device_that_accepts_or_refuses_is_there() {
        let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
            .await
            .unwrap();
        let open = listener.local_addr().unwrap().port();
        // A port nobody listens on: the system refuses, the device is there.
        let closed = {
            let l = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
            l.local_addr().unwrap().port()
        };
        assert!(knock(Ipv4Addr::LOCALHOST, open).await);
        assert!(knock(Ipv4Addr::LOCALHOST, closed).await);
        let there = present(&[Ipv4Addr::LOCALHOST], &[closed]).await;
        assert_eq!(there, [Ipv4Addr::LOCALHOST]);
    }

    #[test]
    fn a_description_gives_name_maker_and_model() {
        let xml = "<root><device><friendlyName>Salon TV</friendlyName>\
            <manufacturer>Fictional &amp; Co</manufacturer><modelName>Screen</modelName>\
            <modelNumber>X-1</modelNumber></device></root>";
        assert_eq!(
            described(xml),
            Found {
                name: Some("Salon TV".into()),
                maker: Some("Fictional & Co".into()),
                model: Some("Screen X-1".into()),
                mac: None,
            }
        );
        let answer = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.20:49152/desc.xml\r\n\r\n";
        let at = location(answer).unwrap();
        assert_eq!(
            (at.host.as_str(), at.port, at.path.as_str()),
            ("192.168.1.20", 49152, "/desc.xml")
        );
    }

    /// A DNS server answering a PTR question with `name`, compressed as a
    /// real one does (the answer's owner points at the question).
    async fn name_server(name: &'static str) -> u16 {
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let port = socket.local_addr().unwrap().port();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            while let Ok((n, from)) = socket.recv_from(&mut buf).await {
                let question = &buf[12..n];
                let mut answer = buf[..2].to_vec();
                answer.extend_from_slice(&[0x81, 0x80, 0, 1, 0, 1, 0, 0, 0, 0]);
                answer.extend_from_slice(question);
                answer.extend_from_slice(&[0xc0, 12, 0, 12, 0, 1, 0, 0, 0, 60]);
                let mut rdata = Vec::new();
                for label in name.split('.') {
                    rdata.push(u8::try_from(label.len()).unwrap());
                    rdata.extend_from_slice(label.as_bytes());
                }
                rdata.push(0);
                answer.extend_from_slice(&u16::try_from(rdata.len()).unwrap().to_be_bytes());
                answer.extend_from_slice(&rdata);
                socket.send_to(&answer, from).await.unwrap();
            }
        });
        port
    }

    #[tokio::test]
    async fn names_come_from_the_box_else_from_the_device() {
        let from_box = name_server("phone-of-someone.home").await;
        let ip = Ipv4Addr::new(192, 168, 1, 30);
        assert_eq!(
            ask_name(Ipv4Addr::LOCALHOST, from_box, ip, true)
                .await
                .as_deref(),
            Some("phone-of-someone")
        );
        // No box that answers: the device's own mDNS answer.
        let device = name_server("living-room.local").await;
        let silent = {
            let s = std::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
            s.local_addr().unwrap().port()
        };
        let got = names(&[Ipv4Addr::LOCALHOST], None, silent, device).await;
        assert_eq!(
            got.get(&Ipv4Addr::LOCALHOST).map(String::as_str),
            Some("living-room")
        );
        let got = names(
            &[Ipv4Addr::LOCALHOST],
            Some(Ipv4Addr::LOCALHOST),
            from_box,
            device,
        )
        .await;
        assert_eq!(
            got.get(&Ipv4Addr::LOCALHOST).map(String::as_str),
            Some("phone-of-someone")
        );
    }

    #[test]
    fn a_ptr_question_is_well_formed() {
        let q = ptr_question(7, Ipv4Addr::new(192, 168, 1, 30), true);
        assert_eq!(&q[..4], &[0, 7, 1, 0]);
        assert_eq!(read_name(&q, 12).unwrap().0, "30.1.168.192.in-addr.arpa");
        // An error answer gives no name.
        let mut refused = q.clone();
        refused[2] = 0x81;
        refused[3] = 0x83;
        assert_eq!(ptr_answer(&refused, 7), None);
        assert_eq!(colons("aabbccddeeff"), "aa:bb:cc:dd:ee:ff");
    }
}
