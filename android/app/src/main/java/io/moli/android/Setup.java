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

    /** Where the account's plugs answer on the home network: `{uuid: address}` (plugs.rs). */
    static JSONObject merossFind(Context context, String key, JSONArray uuids) throws Exception {
        JSONObject body = new JSONObject().put("key", key).put("uuids", uuids);
        String own = homeAddress(context);
        if (own != null) body.put("own", own);
        return new JSONObject(call("meross_find", config(context).getPath(), "", body.toString()));
    }

    /**
     * The tablet's IPv4 on the home network (Wi-Fi or Ethernet), never a
     * VPN's: with a VPN on, the default route is the VPN's, not the home's.
     * Null if none (plugs.rs then takes the default route's).
     */
    static String homeAddress(Context context) {
        android.net.LinkProperties link = homeLink(context);
        if (link == null) return null;
        for (android.net.LinkAddress a : link.getLinkAddresses())
            if (a.getAddress() instanceof java.net.Inet4Address) return a.getAddress().getHostAddress();
        return null;
    }

    /** The box: the IPv4 gateway of the home network's default route. Null if none. */
    static String homeGateway(Context context) {
        android.net.LinkProperties link = homeLink(context);
        if (link == null) return null;
        for (android.net.RouteInfo r : link.getRoutes())
            if (r.isDefaultRoute() && r.getGateway() instanceof java.net.Inet4Address)
                return r.getGateway().getHostAddress();
        return null;
    }

    /** The Wi-Fi or Ethernet link, never a VPN's. */
    private static android.net.LinkProperties homeLink(Context context) {
        android.net.ConnectivityManager cm = context.getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return null;
        for (android.net.Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            android.net.LinkProperties link = cm.getLinkProperties(n);
            if (caps == null || link == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return link;
        }
        return null;
    }

    /**
     * The devices of the home network (network.rs `scan`): `[{ip, name,
     * maker, model, mac, role}]`, the empty ones null. Network: never on the UI thread.
     */
    static JSONArray networkScan(Context context) throws Exception {
        JSONObject body = new JSONObject();
        String own = homeAddress(context), gateway = homeGateway(context);
        if (own != null) body.put("own", own);
        if (gateway != null) body.put("gateway", gateway);
        return new JSONArray(call("network_scan", config(context).getPath(), "", body.toString()));
    }

    /**
     * Adds `plugs` ([{host, name}]) as « Ajouter un appareil » does, in a
     * session opened with the house's code: `{host: {outcome, instance}}`.
     */
    static JSONObject merossAdd(Context context, String key, JSONArray plugs) throws Exception {
        String pin = ProtectedKey.open(context)[1];
        String body = new JSONObject().put("port", MoliService.port).put("pin", pin)
                .put("key", key).put("plugs", plugs).toString();
        return new JSONObject(call("meross_add", config(context).getPath(), "", body));
    }

    /**
     * Which of the account's plugs the house runs (plugs.rs `house`):
     * `{uuid: {id, name}}`, the name as the dashboard shows it.
     */
    static JSONObject merossHouse(Context context, JSONArray uuids) throws Exception {
        String pin = ProtectedKey.open(context)[1];
        String body = new JSONObject().put("port", MoliService.port).put("pin", pin)
                .put("uuids", uuids).toString();
        return new JSONObject(call("meross_house", config(context).getPath(), "", body));
    }

    /** The Meross plugs of the house, without the account (plugs.rs `installed`). */
    static JSONObject merossInstalled(Context context) throws Exception {
        String pin = ProtectedKey.open(context)[1];
        String body = new JSONObject().put("port", MoliService.port).put("pin", pin).toString();
        return new JSONObject(call("meross_installed", config(context).getPath(), "", body));
    }

    /** Renames devices ([{id, name}]) as the dashboard does: `{id: "renamed" | Moli's error}`. */
    static JSONObject merossRename(Context context, JSONArray devices) throws Exception {
        String pin = ProtectedKey.open(context)[1];
        String body = new JSONObject().put("port", MoliService.port).put("pin", pin)
                .put("devices", devices).toString();
        return new JSONObject(call("meross_rename", config(context).getPath(), "", body));
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

    /**
     * Takes a plug added from the dashboard out of the house: Moli stopped,
     * its entry out of `devices-added.json`, Moli started again, its key
     * forgotten. Null when done; else why (`not_added_here`: written in moli.toml).
     */
    static String removePlug(Context context, String instance) throws Exception {
        String key = ProtectedKey.open(context)[0];
        String path = config(context).getPath();
        Backup.stop(context);
        try {
            call("plug_remove", path, "", instance);
        } catch (Exception refused) {
            startAndWait(context);
            return String.valueOf(refused.getMessage());
        }
        String refused = startAndWait(context);
        if (refused == null)
            call("forget", path, key, new JSONObject().put("instance", instance)
                    .put("names", new JSONArray().put("key")).toString());
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
