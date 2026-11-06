//! The JNI boundary of the Android app (`NativeServer.java`): start, watch
//! and stop the official engine ([`moli_os::hosted`]). No logic of its own.

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
