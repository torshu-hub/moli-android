package io.moli.android;

import android.content.Context;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the integrations screen does to the house, in the official files
 * (crates/moli-android, setup.rs): the `[[driver]]` blocks of moli.toml and
 * the vault. Each change stops Moli, writes, starts it again, and puts the
 * previous moli.toml back if Moli refuses the new one. Never on the UI thread.
 */
final class Setup {
    static { System.loadLibrary("moli_android"); }

    /** One entry point (setup.rs `call`): throws IllegalStateException with the reason. */
    private static native String call(String action, String config, String key, String body);

    private Setup() {}

    static File config(Context context) { return MoliService.config(context); }

    static JSONArray catalogue(Context context) throws Exception {
        return new JSONArray(call("catalogue", config(context).getPath(), "", ""));
    }

    static JSONArray drivers(Context context) throws Exception {
        return new JSONArray(call("drivers", config(context).getPath(), "", ""));
    }

    /**
     * The key of a Meross account (meross.rs): `{"key": …}`, or Meross's
     * refusal `{"status": …, "info": …}`. Network: never on the UI thread.
     */
    static JSONObject merossKey(Context context, String email, String password, String code) throws Exception {
        JSONObject body = new JSONObject().put("email", email).put("password", password);
        if (code != null && !code.trim().isEmpty()) body.put("code", code.trim());
        return new JSONObject(call("meross_key", config(context).getPath(), "", body.toString()));
    }

    /** Which of `names` the vault holds for `instance` (never their values). */
    static JSONArray stored(Context context, String instance, JSONArray names) throws Exception {
        if (names.length() == 0) return new JSONArray();
        String body = new JSONObject().put("instance", instance).put("names", names).toString();
        return new JSONArray(call("stored", config(context).getPath(), ProtectedKey.open(context)[0], body));
    }

    /**
     * Writes one block (`change`, setup.rs `Change`) and its secrets, then
     * restarts Moli. Null when Moli runs with it; else Moli's reason, and
     * the house is as it was.
     */
    static String put(Context context, JSONObject change, String instance, JSONObject secrets) throws Exception {
        String key = ProtectedKey.open(context)[0];
        String path = config(context).getPath();
        Backup.stop(context);
        if (secrets.length() > 0)
            call("store", path, key, new JSONObject().put("instance", instance).put("values", secrets).toString());
        call("put", path, "", change.toString());
        return restart(context, path);
    }

    /** Removes one block, restarts Moli, then forgets its secrets. */
    static String remove(Context context, String instance, JSONArray secretNames) throws Exception {
        String key = ProtectedKey.open(context)[0];
        String path = config(context).getPath();
        Backup.stop(context);
        call("remove", path, "", instance);
        String refused = restart(context, path);
        if (refused == null && secretNames.length() > 0)
            call("forget", path, key, new JSONObject().put("instance", instance).put("names", secretNames).toString());
        return refused;
    }

    private static String restart(Context context, String path) throws InterruptedException {
        String refused = startAndWait(context);
        if (refused == null) {
            call("settle", path, "", "");
            return null;
        }
        // Moli's own check said no: the previous file, and Moli, back.
        Backup.stop(context);
        call("rollback", path, "", "");
        startAndWait(context);
        return refused;
    }

    /** Starts Moli and waits until it answers (null) or says why it cannot. */
    static String startAndWait(Context context) throws InterruptedException {
        MoliService.refused = null;
        MoliService.start(context);
        long end = System.currentTimeMillis() + 60_000;
        boolean seen = false;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(250);
            if (MoliService.refused != null) return MoliService.refused;
            boolean running = NativeServer.running();
            seen |= running || MoliService.starting;
            if (running && ManagementActivity.healthy(MoliService.port)) return null;
            // Started, then ended by itself: its configuration was refused.
            if (seen && !running && !MoliService.starting && NativeServer.failure() != null)
                return NativeServer.failure();
        }
        return context.getString(R.string.setup_timeout);
    }
}
