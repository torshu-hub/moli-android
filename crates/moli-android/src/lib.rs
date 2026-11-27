//! The JNI boundary of the Android app: `NativeServer.java` starts, watches
//! and stops the official engine ([`moli_os::hosted`]), with no logic of its
//! own; `Setup.java` is the integrations screen ([`setup`]).

pub mod energy;
pub mod home;
pub mod journal;
pub mod meross;
pub mod netatmo;
pub mod network;
pub mod plugs;
pub mod sections;
pub mod setup;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::{Mutex, MutexGuard, PoisonError};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use moli_os::hosted::Hosted;
use moli_runtime::MasterKey;

/// The running engine, and why the last one ended (if it failed).
struct Slot {
    running: Option<Hosted>,
    failure: Option<String>,
}

static SLOT: Mutex<Slot> = Mutex::new(Slot {
    running: None,
    failure: None,
});

fn slot() -> MutexGuard<'static, Slot> {
    SLOT.lock().unwrap_or_else(PoisonError::into_inner)
}

/// An engine that ended by itself is joined: its error is kept for the screen.
fn reap(slot: &mut Slot) {
    if slot.running.as_ref().is_some_and(|m| !m.running())
        && let Some(ended) = slot.running.take()
    {
        slot.failure = Some(match ended.stop() {
            Ok(()) => "Moli stopped".to_owned(),
            Err(e) => format!("{e:#}"),
        });
    }
}

fn start(config: &str, key: String, pin: String) -> anyhow::Result<u16> {
    let mut slot = slot();
    reap(&mut slot);
    if let Some(running) = &slot.running {
        return Ok(running.port());
    }
    slot.failure = None;
    journal::start(Path::new(config));
    let moli = Hosted::start(Path::new(config), MasterKey::new(key), pin)?;
    let port = moli.port();
    slot.running = Some(moli);
    Ok(port)
}

fn stop() -> anyhow::Result<()> {
    let running = slot().running.take();
    running.map_or(Ok(()), Hosted::stop)
}

fn throw(env: &mut JNIEnv<'_>, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

/// `static native int start(String config, String key, String pin)`: the port.
// SAFETY: name and signature match NativeServer.java; this library ships in
// the APK, never loaded from writable storage.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_moli_android_NativeServer_start(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
    key: JString<'_>,
    pin: JString<'_>,
) -> jint {
    let config: Option<String> = env.get_string(&config).ok().map(Into::into);
    let key: Option<String> = env.get_string(&key).ok().map(Into::into);
    let pin: Option<String> = env.get_string(&pin).ok().map(Into::into);
    let (Some(config), Some(key), Some(pin)) = (config, key, pin) else {
        throw(&mut env, "invalid arguments");
        return 0;
    };
    match catch_unwind(AssertUnwindSafe(|| start(&config, key, pin))) {
        Ok(Ok(port)) => jint::from(port),
        Ok(Err(e)) => {
            // Errors name files and settings, never the key or the code.
            throw(&mut env, &format!("{e:#}"));
            0
        }
        Err(_) => {
            throw(&mut env, "Moli panicked");
            0
        }
    }
}

/// `static native void stop()`: waits for Moli to stop (off the UI thread).
// SAFETY: matches NativeServer.java.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_moli_android_NativeServer_stop(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    match catch_unwind(stop) {
        Ok(Ok(())) => {}
        Ok(Err(e)) => throw(&mut env, &format!("{e:#}")),
        Err(_) => throw(&mut env, "Moli panicked"),
    }
}

/// `static native boolean running()`.
// SAFETY: matches NativeServer.java.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_moli_android_NativeServer_running(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    let mut slot = slot();
    reap(&mut slot);
    jboolean::from(slot.running.is_some())
}

/// `static native String failure()`: why the last engine ended, or null.
// SAFETY: matches NativeServer.java.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_moli_android_NativeServer_failure(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let failure = {
        let mut slot = slot();
        reap(&mut slot);
        slot.failure.clone()
    };
    failure
        .and_then(|f| env.new_string(f).ok())
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

// ---- Setup.java: the integrations screen ([`setup`]). ----

fn text(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Option<String> {
    env.get_string(value).ok().map(Into::into)
}

/// Runs `work` and gives its text to Java; its error is thrown
/// (`IllegalStateException`), a panic too.
fn answer(env: &mut JNIEnv<'_>, work: impl FnOnce() -> anyhow::Result<String>) -> jstring {
    match catch_unwind(AssertUnwindSafe(work)) {
        Ok(Ok(out)) => env
            .new_string(out)
            .map_or(std::ptr::null_mut(), JString::into_raw),
        Ok(Err(e)) => {
            throw(env, &format!("{e:#}"));
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(env, "Moli panicked");
            std::ptr::null_mut()
        }
    }
}

/// `static native String call(String action, String config, String key, String body)`:
/// one entry point for the screen, `action` naming what to do (keeps the
/// JNI surface to one symbol). Never prints a secret; returns one only for
/// `meross_key`, the account's key the person asked for (to paste it).
// SAFETY: name and signature match Setup.java; this library ships in the APK.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_moli_android_Setup_call(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    action: JString<'_>,
    config: JString<'_>,
    key: JString<'_>,
    body: JString<'_>,
) -> jstring {
    let (Some(action), Some(config), Some(key), Some(body)) = (
        text(&mut env, &action),
        text(&mut env, &config),
        text(&mut env, &key),
        text(&mut env, &body),
    ) else {
        throw(&mut env, "invalid arguments");
        return std::ptr::null_mut();
    };
    answer(&mut env, || call(&action, Path::new(&config), key, &body))
}

/// The secrets of one instance: `{"instance": "…", "names": […]}` or `"values": {…}`.
#[derive(serde::Deserialize)]
struct Secrets {
    instance: String,
    #[serde(default)]
    names: Vec<String>,
    #[serde(default)]
    values: std::collections::BTreeMap<String, String>,
}

fn call(action: &str, config: &Path, key: String, body: &str) -> anyhow::Result<String> {
    let key = MasterKey::new(key);
    let secrets = || serde_json::from_str::<Secrets>(body);
    Ok(match action {
        "catalogue" => setup::catalogue(),
        "drivers" => setup::drivers(config)?,
        "put" => {
            setup::put(config, &serde_json::from_str(body)?)?;
            String::new()
        }
        "remove" => {
            setup::remove(config, body)?;
            String::new()
        }
        "rollback" => {
            setup::rollback(config)?;
            String::new()
        }
        "settle" => {
            setup::settle(config);
            String::new()
        }
        "stored" => {
            let s = secrets()?;
            serde_json::to_string(&setup::stored(config, &key, &s.instance, &s.names)?)?
        }
        "store" => {
            let s = secrets()?;
            setup::store(config, &key, &s.instance, &s.values)?;
            String::new()
        }
        "forget" => {
            let s = secrets()?;
            setup::forget(config, &key, &s.instance, &s.names)?;
            String::new()
        }
        "netatmo_begin" => netatmo::begin(config, &key, &serde_json::from_str(body)?)?.to_string(),
        "netatmo_wait" => netatmo::wait(config, &key, body)?.to_string(),
        "netatmo_cancel" => {
            netatmo::cancel();
            String::new()
        }
        "journal" => journal::tail(config),
        "meross_key" => meross::key(&serde_json::from_str(body)?)?.to_string(),
        "meross_find" => plugs::find(&serde_json::from_str(body)?)?.to_string(),
        "meross_add" => plugs::add(&serde_json::from_str(body)?)?.to_string(),
        "meross_house" => plugs::house(&serde_json::from_str(body)?)?.to_string(),
        "meross_rename" => plugs::rename(&serde_json::from_str(body)?)?.to_string(),
        "meross_installed" => plugs::installed(config, &serde_json::from_str(body)?)?.to_string(),
        "plug_remove" => plugs::take(&serde_json::from_str(body)?)?,
        "energy" => energy::read(config)?,
        "energy_put" => {
            energy::put(config, body)?;
            String::new()
        }
        "energy_preview" => energy::preview(config, body)?.to_string(),
        "energy_price" => energy::price(&serde_json::from_str(body)?)?,
        "energy_points" => energy::points(config, &serde_json::from_str(body)?)?.to_string(),
        "home" => home::read(config)?.to_string(),
        "home_put" => {
            home::put(config, body)?;
            String::new()
        }
        "home_preview" => home::preview(config, body)?.to_string(),
        "home_house" => home::house(&serde_json::from_str(body)?)?.to_string(),
        "sections" => {
            sections::read(config, &serde_json::from_str::<Vec<String>>(body)?)?.to_string()
        }
        "sections_put" => {
            sections::put(config, body)?;
            String::new()
        }
        "sections_preview" => sections::preview(config, body)?.to_string(),
        "network_scan" => network::scan(&serde_json::from_str(body)?)?.to_string(),
        other => anyhow::bail!("unknown action {other:?}"),
    })
}
