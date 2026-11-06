#!/bin/sh
# The engine for the Android app (ARM64): crates/moli-android as
# libmoli_android.so, where Gradle packs it. Needs web/dist (the dashboard),
# the Rust target aarch64-linux-android and the NDK below.
set -eu
cd "$(dirname "$0")/.."
ndk_version=${MOLI_NDK_VERSION:-28.2.13676358}
api=28
ndk=${ANDROID_HOME:?Set ANDROID_HOME}/ndk/$ndk_version
test -s web/dist/index.html
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$bin/aarch64-linux-android$api-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$bin/llvm-ar"
# 16 KB pages: what recent Android devices require of native libraries.
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
cargo build --release --locked -p moli-android --target aarch64-linux-android
mkdir -p android/app/src/main/jniLibs/arm64-v8a
cp "${CARGO_TARGET_DIR:-target}/aarch64-linux-android/release/libmoli_android.so" android/app/src/main/jniLibs/arm64-v8a/
