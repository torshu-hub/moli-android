package io.moli.android;

/** The official engine, in this process (crates/moli-android, libmoli_android.so). */
final class NativeServer {
    static { System.loadLibrary("moli_android"); }

    /** Starts Moli; returns its port. Throws IllegalStateException with the reason. */
    static native int start(String config, String key, String pin);

    /** Stops Moli and waits for it (never on the UI thread). */
    static native void stop();

    static native boolean running();

    /** Why the last Moli ended by itself, or null. */
    static native String failure();

    private NativeServer() {}
}
