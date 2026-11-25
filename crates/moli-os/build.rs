//! The integration catalogue, carried by the binary: each package's manifest
//! and onboarding guide (`integrations/<id>/`), for the dashboard's setup page.
//! A host without the repository (the Android app) still knows them.

use std::fmt::Write as _;
use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").expect("cargo sets it"));
    let root = manifest_dir.join("../../integrations");
    println!("cargo:rerun-if-changed={}", root.display());
    let mut packages: Vec<PathBuf> = std::fs::read_dir(&root)
        .map(|dir| {
            dir.filter_map(Result::ok)
                .map(|entry| entry.path())
                .filter(|path| path.join("integration.toml").is_file())
                .collect()
        })
        .unwrap_or_default();
    packages.sort();
    let mut out = String::from("pub const PACKAGES: &[(&str, &str, &str)] = &[\n");
    for dir in packages {
        let id = dir
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or_default()
            .to_owned();
        let manifest = dir
            .join("integration.toml")
            .canonicalize()
            .expect("readable manifest");
        let onboarding = dir.join("onboarding.md");
        println!("cargo:rerun-if-changed={}", manifest.display());
        let guide = if onboarding.is_file() {
            let onboarding = onboarding.canonicalize().expect("readable onboarding");
            println!("cargo:rerun-if-changed={}", onboarding.display());
            format!("include_str!({:?})", onboarding.display().to_string())
        } else {
            "\"\"".to_owned()
        };
        writeln!(
            out,
            "    ({id:?}, include_str!({:?}), {guide}),",
            manifest.display().to_string()
        )
        .expect("writing to a string");
    }
    out.push_str("];\n");
    let target =
        PathBuf::from(std::env::var("OUT_DIR").expect("cargo sets it")).join("packages.rs");
    std::fs::write(target, out).expect("writable OUT_DIR");
}
