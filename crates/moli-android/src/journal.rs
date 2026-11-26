//! The engine's journal (DECISIONS 8): what Moli and the app say while an
//! integration is configured, kept in a file the screen shows and exports.
//! Android keeps no terminal, and `tracing` had nowhere to write: the file
//! is that place. It is capped (two files of about 400 kB, the older one
//! replaced when the newer is full) and it holds what the engine logs at
//! `info`: no secret (the vault and the drivers never log one).

use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock, PoisonError};

use tracing_subscriber::fmt::MakeWriter;

/// A file is full at this size.
const CAP: u64 = 400 * 1024;
/// What the screen reads back at most.
const TAIL: usize = 96 * 1024;

fn path(config: &Path) -> PathBuf {
    config
        .parent()
        .unwrap_or(Path::new("."))
        .join("journal.log")
}

struct Inner {
    path: PathBuf,
    file: Option<File>,
    size: u64,
}

struct Journal(Mutex<Inner>);

impl Inner {
    fn open(&mut self) -> io::Result<&mut File> {
        if self.file.is_none() {
            let file = OpenOptions::new()
                .create(true)
                .append(true)
                .open(&self.path)?;
            self.size = file.metadata()?.len();
            self.file = Some(file);
        }
        self.file
            .as_mut()
            .ok_or_else(|| io::Error::other("no journal"))
    }

    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.open()?;
        if self.size >= CAP {
            self.file = None;
            let _ = std::fs::rename(&self.path, self.path.with_extension("old"));
            self.open()?;
        }
        let file = self.open()?;
        file.write_all(buf)?;
        self.size += buf.len() as u64;
        Ok(buf.len())
    }
}

struct Handle<'a>(&'a Journal);

impl Write for Handle<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.0
            .0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .write(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl<'a> MakeWriter<'a> for Journal {
    type Writer = Handle<'a>;

    fn make_writer(&'a self) -> Handle<'a> {
        Handle(self)
    }
}

static STARTED: OnceLock<()> = OnceLock::new();

/// Starts writing the journal (once per run; a failure leaves the engine as
/// it was, without a journal).
pub fn start(config: &Path) {
    STARTED.get_or_init(|| {
        let journal = Journal(Mutex::new(Inner {
            path: path(config),
            file: None,
            size: 0,
        }));
        let _ = tracing_subscriber::fmt()
            .with_env_filter(
                tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| {
                    tracing_subscriber::EnvFilter::new("info,rumqttc=warn,rmcp=warn")
                }),
            )
            .with_writer(journal)
            .with_ansi(false)
            .with_target(false)
            .try_init();
    });
}

/// The end of the journal, whole lines: what the screen shows and exports.
pub fn tail(config: &Path) -> String {
    let file = path(config);
    let mut text = String::new();
    for candidate in [file.with_extension("old"), file] {
        text.push_str(&read_tail(&candidate));
    }
    let lines: Vec<&str> = text.lines().collect();
    let mut out = lines[lines.len().saturating_sub(600)..].join("\n");
    if out.len() > TAIL {
        out = out[out.len() - TAIL..].to_owned();
        out = out
            .split_once('\n')
            .map_or(String::new(), |(_, rest)| rest.to_owned());
    }
    out
}

fn read_tail(file: &Path) -> String {
    let Ok(mut f) = File::open(file) else {
        return String::new();
    };
    let len = f.metadata().map_or(0, |m| m.len());
    let from = len.saturating_sub(TAIL as u64);
    if f.seek(SeekFrom::Start(from)).is_err() {
        return String::new();
    }
    let mut bytes = Vec::new();
    let _ = f.read_to_end(&mut bytes);
    let mut text = String::from_utf8_lossy(&bytes).into_owned();
    if from > 0 {
        text = text
            .split_once('\n')
            .map_or(String::new(), |(_, rest)| rest.to_owned());
    }
    if !text.is_empty() && !text.ends_with('\n') {
        text.push('\n');
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dir(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("moli-journal-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn the_journal_keeps_its_lines_and_is_read_back_from_the_end() {
        let d = dir("lines");
        let config = d.join("moli.toml");
        let mut inner = Inner {
            path: path(&config),
            file: None,
            size: 0,
        };
        for n in 0..5 {
            inner.write(format!("line {n}\n").as_bytes()).unwrap();
        }
        assert_eq!(tail(&config), "line 0\nline 1\nline 2\nline 3\nline 4");
        let _ = std::fs::remove_dir_all(d);
    }

    #[test]
    fn a_full_journal_replaces_the_older_one_and_both_are_read() {
        let d = dir("full");
        let config = d.join("moli.toml");
        let mut inner = Inner {
            path: path(&config),
            file: None,
            size: 0,
        };
        let line = format!("{}\n", "x".repeat(1023));
        for _ in 0..(CAP / 1024 + 3) {
            inner.write(line.as_bytes()).unwrap();
        }
        inner.write(b"last\n").unwrap();
        assert!(path(&config).with_extension("old").exists());
        assert!(inner.size < CAP);
        assert!(tail(&config).ends_with("last"));
        let _ = std::fs::remove_dir_all(d);
    }

    #[test]
    fn no_journal_is_an_empty_text() {
        assert_eq!(tail(&dir("none").join("moli.toml")), "");
    }
}
