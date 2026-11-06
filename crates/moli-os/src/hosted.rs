//! Moli inside another program (the Android app): the same `serve`, with the
//! master key and the dashboard code given by the host (instead of
//! `MOLI_MASTER_KEY` and `MOLI_UI_PIN`), and stopped by the host (instead of
//! a signal). Nothing else differs from the `moli-os` command.

use std::path::Path;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use anyhow::Context as _;
use moli_runtime::MasterKey;
use tokio_util::sync::CancellationToken;

use crate::config::Config;

/// What the host gives `serve`.
pub(crate) struct Host {
    pub(crate) key: MasterKey,
    pub(crate) pin: String,
    pub(crate) stop: CancellationToken,
}

/// The master key: the host's, else as `moli-os serve` finds it.
pub(crate) fn master_key(
    host: Option<&Host>,
    data_dir: &Path,
) -> anyhow::Result<Option<MasterKey>> {
    match host {
        Some(host) => Ok(Some(host.key.clone())),
        None => crate::master_key(data_dir, true),
    }
}

/// The dashboard code: the host's, else `MOLI_UI_PIN`.
pub(crate) fn ui_pin(host: Option<&Host>) -> anyhow::Result<Option<String>> {
    match host {
        Some(host) => Ok(Some(host.pin.clone())),
        None => crate::from_env_or_file("MOLI_UI_PIN"),
    }
}

/// Until the host stops Moli, else until a signal.
pub(crate) async fn stopped(host: Option<Host>) {
    match host {
        Some(host) => host.stop.cancelled().await,
        None => crate::wait_for_signal().await,
    }
}

/// A running Moli, on its own thread.
#[derive(Debug)]
pub struct Hosted {
    port: u16,
    stop: CancellationToken,
    worker: JoinHandle<anyhow::Result<()>>,
}

/// How long a stop may take before Moli is left behind.
const STOP_TIMEOUT: Duration = Duration::from_secs(10);

impl Hosted {
    /// Starts Moli from the configuration at `config` (a new house's is
    /// written there, its data beside it, as `moli-os serve` does).
    pub fn start(config: &Path, key: MasterKey, pin: String) -> anyhow::Result<Self> {
        let boot = Instant::now();
        let config = if !config.exists() && crate::fresh(config) {
            Config::starter(config)?
        } else {
            Config::load(config)?
        };
        let port = config.server.listen.port();
        let stop = CancellationToken::new();
        let host = Host {
            key,
            pin,
            stop: stop.clone(),
        };
        let stopping = stop.clone();
        let worker = std::thread::Builder::new()
            .name("moli".into())
            .spawn(move || {
                let runtime = tokio::runtime::Builder::new_multi_thread()
                    .worker_threads(config.server.worker_threads)
                    .enable_all()
                    .build()?;
                let result = runtime.block_on(async {
                    let serving = crate::serve(config, boot, Some(host));
                    tokio::pin!(serving);
                    tokio::select! {
                        result = &mut serving => result,
                        () = stopping.cancelled() => tokio::time::timeout(STOP_TIMEOUT, serving)
                            .await
                            .context("Moli did not stop within 10 seconds")?,
                    }
                });
                runtime.shutdown_timeout(Duration::from_secs(2));
                result
            })?;
        Ok(Self { port, stop, worker })
    }

    /// The port Moli listens on (the configuration's).
    pub fn port(&self) -> u16 {
        self.port
    }

    /// Whether it still runs: false once it stopped by itself (an error).
    pub fn running(&self) -> bool {
        !self.worker.is_finished()
    }

    /// Stops Moli and waits for it; its error, if it failed.
    pub fn stop(self) -> anyhow::Result<()> {
        self.stop.cancel();
        self.worker
            .join()
            .map_err(|_| anyhow::anyhow!("Moli panicked"))?
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read as _, Write as _};

    fn health(port: u16) -> Option<String> {
        let mut socket = std::net::TcpStream::connect(("127.0.0.1", port)).ok()?;
        socket.set_read_timeout(Some(Duration::from_secs(5))).ok()?;
        write!(
            socket,
            "GET /api/health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        .ok()?;
        let mut response = String::new();
        socket.read_to_string(&mut response).ok()?;
        Some(response)
    }

    fn wait_healthy(port: u16) -> String {
        let end = Instant::now() + Duration::from_secs(20);
        while Instant::now() < end {
            if let Some(response) = health(port).filter(|r| r.starts_with("HTTP/1.1 200")) {
                return response;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        panic!("Moli never answered /api/health");
    }

    #[test]
    fn starts_answers_stops_and_restarts_without_a_key_file() {
        let dir = std::env::temp_dir().join(format!("moli-hosted-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let port = std::net::TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let config = dir.join("moli.toml");
        std::fs::write(
            &config,
            format!(
                "[server]\nlisten = \"127.0.0.1:{port}\"\ndata_dir = {}\n",
                toml::Value::String(dir.display().to_string())
            ),
        )
        .unwrap();
        let key = MasterKey::generate().unwrap();

        let moli = Hosted::start(&config, key.clone(), "482913".into()).unwrap();
        assert_eq!(moli.port(), port);
        assert!(wait_healthy(port).contains("moli-os"));
        assert!(moli.running());
        moli.stop().unwrap();
        assert!(std::net::TcpStream::connect(("127.0.0.1", port)).is_err());
        // The key stays with the host: never a key file beside the data.
        assert!(!dir.join("master.key").exists());
        assert!(dir.join("state.json").exists());

        let again = Hosted::start(&config, key, "482913".into()).unwrap();
        wait_healthy(port);
        again.stop().unwrap();
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
