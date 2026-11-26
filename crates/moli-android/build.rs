//! The official catalogue (`integrations/<id>/integration.toml` and
//! `onboarding.md`), packed into the library for the integrations screen:
//! the tablet has no copy of the repository.

use std::fmt::Write as _;
use std::path::Path;

fn main() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../integrations");
    println!("cargo:rerun-if-changed={}", root.display());
    let mut ids: Vec<String> = std::fs::read_dir(&root)
        .expect("the catalogue (integrations/)")
        .filter_map(Result::ok)
        .filter(|entry| entry.path().join("integration.toml").is_file())
        .map(|entry| entry.file_name().to_string_lossy().into_owned())
        .collect();
    ids.sort();
    let mut out = String::from("&[\n");
    for id in &ids {
        let dir = root.join(id);
        println!("cargo:rerun-if-changed={}", dir.display());
        let manifest = dir.join("integration.toml");
        let onboarding = dir.join("onboarding.md");
        let onboarding = if onboarding.is_file() {
            format!("include_str!({:?})", onboarding.display().to_string())
        } else {
            "\"\"".to_owned()
        };
        writeln!(
            out,
            "    ({id:?}, include_str!({:?}), {onboarding}),",
            manifest.display().to_string()
        )
        .unwrap();
    }
    out.push(']');
    let target = Path::new(&std::env::var("OUT_DIR").unwrap()).join("catalogue.rs");
    std::fs::write(target, out).unwrap();
}
