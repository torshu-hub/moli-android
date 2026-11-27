package io.moli.android;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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

    /**
     * Connects Netatmo (netatmo.rs): opens Netatmo's page in the browser,
     * hears the redirection, keeps the tokens in the vault, then writes the
     * block as `put` does. Null when Moli runs with it; else the reason, in
     * words. `restarting` is told when the acceptance came and Moli restarts.
     * Blocks until the person accepts (five minutes at most): never on the UI thread.
     */
    static String netatmoConnect(Context context, JSONObject change, String instance, JSONObject secrets, Runnable restarting) throws Exception {
        String key = ProtectedKey.open(context)[0];
        String path = config(context).getPath();
        JSONObject body = new JSONObject().put("instance", instance)
                .put("client_id", secrets.optString("client_id", ""))
                .put("client_secret", secrets.optString("client_secret", ""));
        JSONObject begun = new JSONObject(call("netatmo_begin", path, key, body.toString()));
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(begun.getString("url")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException noBrowser) {
            call("netatmo_cancel", path, "", "");
            throw noBrowser;
        }
        JSONObject heard = new JSONObject(call("netatmo_wait", path, key, instance));
        String info = heard.optString("info");
        switch (heard.optString("status")) {
            case "connected": break;
            case "denied": return context.getString(R.string.netatmo_denied, info);
            case "refused": return context.getString(R.string.netatmo_refused, info);
            case "timeout": return context.getString(R.string.netatmo_timeout);
            default: return context.getString(R.string.netatmo_cancelled);
        }
        restarting.run();
        return put(context, change, instance, new JSONObject());
    }

    /**
     * The house's `[energy]` section and off-peak hours (energy.rs `read`):
     * `{section, offpeak: [...], offpeak_point}`.
     */
    static JSONObject energy(Context context) throws Exception {
        return new JSONObject(call("energy", config(context).getPath(), "", ""));
    }

    /**
     * Writes the whole `[energy]` section (`{}` takes it out) and the
     * off-peak hours (null: left as they are; empty: taken out) once the
     * official checks accept them, then restarts Moli as `put` does. Null when
     * Moli runs with it; else the reason, and the house is as it was.
     */
    static String energyPut(Context context, JSONObject section, JSONArray offpeak) throws Exception {
        String path = config(context).getPath();
        JSONObject body = new JSONObject().put("section", section);
        if (offpeak != null) body.put("offpeak", offpeak);
        try {
            call("energy_put", path, "", body.toString());
        } catch (IllegalStateException refused) {
            // Refused by the check: nothing written, Moli left running.
            return String.valueOf(refused.getMessage());
        }
        Backup.stop(context);
        return restart(context, path);
    }

    /**
     * « Voir ce qui sera écrit » (energy.rs `preview`): `{before, after,
     * error}`, the screen's part of moli.toml now and once written. Writes nothing.
     */
    static JSONObject energyPreview(Context context, JSONObject section, JSONArray offpeak) throws Exception {
        JSONObject body = new JSONObject().put("section", section);
        if (offpeak != null) body.put("offpeak", offpeak);
        return new JSONObject(call("energy_preview", config(context).getPath(), "", body.toString()));
    }

    /**
     * « Prix unique »: the house's price of a kWh, as the dashboard's card
     * sets it (Moli running). Null when done; else Moli's reason.
     */
    static String energyPrice(Context context, double price) throws Exception {
        String pin = ProtectedKey.open(context)[1];
        String body = new JSONObject().put("port", MoliService.port).put("pin", pin).put("price", price).toString();
        String refused = call("energy_price", config(context).getPath(), "", body);
        return refused.isEmpty() ? null : refused;
    }

    /**
     * The house's points, what measures, and the house's price of a kWh
     * (energy.rs `points`): `{points: [{id, device, name, label, unit,
     * family, value}], measuring: [{device, name, room, point, family,
     * value, unit, guess, followed}], house_price}`. Moli must run.
     */
    static JSONObject energyPoints(Context context) throws Exception {
        String pin = ProtectedKey.open(context)[1];
        String body = new JSONObject().put("port", MoliService.port).put("pin", pin).toString();
        return new JSONObject(call("energy_points", config(context).getPath(), "", body));
    }

    /** `data/home.json` as written (home.rs `read`): `{layout}`. */
    static JSONObject home(Context context) throws Exception {
        return new JSONObject(call("home", config(context).getPath(), "", ""));
    }

    /**
     * Writes whole top-level keys of home.json (`set`, JSON null takes one
     * out), the others kept; no restart: the dashboard reads it at each look.
     * Null when written; else why not (nothing written).
     */
    static String homePut(Context context, JSONObject set) throws Exception {
        try {
            call("home_put", config(context).getPath(), "", new JSONObject().put("set", set).toString());
            return null;
        } catch (IllegalStateException refused) {
            return String.valueOf(refused.getMessage());
        }
    }

    /** « Voir ce qui sera écrit » for home.json: `{before, after, error}`. Writes nothing. */
    static JSONObject homePreview(Context context, JSONObject set) throws Exception {
        return new JSONObject(call("home_preview", config(context).getPath(), "",
                new JSONObject().put("set", set).toString()));
    }

    /**
     * The house as Moli runs it (home.rs `house`): `{devices: [{id, name,
     * room, kind, switches: [{point, label}]}], rooms: [{name, outside,
     * added}]}`. Moli must run.
     */
    static JSONObject homeHouse(Context context) throws Exception {
        String pin = ProtectedKey.open(context)[1];
        String body = new JSONObject().put("port", MoliService.port).put("pin", pin).toString();
        return new JSONObject(call("home_house", config(context).getPath(), "", body));
    }

    /** Sections of moli.toml as written (sections.rs `read`): `{sections: {name: value or null}}`. */
    static JSONObject sections(Context context, String... names) throws Exception {
        return new JSONObject(call("sections", config(context).getPath(), "", new JSONArray(java.util.Arrays.asList(names)).toString()));
    }

    /** « Voir ce qui sera écrit » for sections of moli.toml: `{before, after, error}`. Writes nothing. */
    static JSONObject sectionsPreview(Context context, JSONObject set) throws Exception {
        return new JSONObject(call("sections_preview", config(context).getPath(), "", new JSONObject().put("set", set).toString()));
    }

    /**
     * Writes whole sections of moli.toml (`set`, JSON null or empty takes one
     * out) once Moli's own checks accept the file, then restarts Moli as `put`
     * does. Null when Moli runs with it; else the reason, the house as it was.
     */
    static String sectionsPut(Context context, JSONObject set) throws Exception {
        String path = config(context).getPath();
        try {
            call("sections_put", path, "", new JSONObject().put("set", set).toString());
        } catch (IllegalStateException refused) {
            // Refused by Moli's checks: nothing written, Moli left running.
            return String.valueOf(refused.getMessage());
        }
        Backup.stop(context);
        return restart(context, path);
    }

    /** The tools agents reach over MCP, as Moli lists them. */
    static JSONArray mcpTools(Context context) throws Exception {
        return new JSONArray(call("mcp_tools", config(context).getPath(), "", ""));
    }

    /** The end of the journal of Moli and of the app (journal.rs). */
    static String journal(Context context) throws Exception {
        return call("journal", config(context).getPath(), "", "");
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
     * Takes a plug added from the dashboard out of the house, or (with
     * `device`) forgets a device of the house that does not answer: Moli's own
     * routes (plugs.rs `take`), Moli running, no restart. Null when done; else
     * Moli's reason (`not_found`: written in moli.toml, or gone).
     */
    static String removePlug(Context context, String instance, String device) throws Exception {
        String pin = ProtectedKey.open(context)[1];
        JSONObject body = new JSONObject().put("port", MoliService.port).put("pin", pin).put("instance", instance);
        if (device != null) body.put("device", device);
        String refused = call("plug_remove", config(context).getPath(), "", body.toString());
        return refused.isEmpty() ? null : refused;
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
