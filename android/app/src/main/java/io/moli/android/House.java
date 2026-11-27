package io.moli.android;

import android.content.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The house as the settings screens choose from it: its devices and their
 * points as Moli runs them (home.rs `house`), its rooms (home.json, then
 * those the devices name). Read once per screen, off the UI thread.
 */
final class House {
    /** The devices (empty when Moli did not answer: `running` false). */
    final List<JSONObject> devices = new ArrayList<>();
    final boolean running;
    private final Map<String, JSONObject> deviceById = new HashMap<>();
    private final Map<String, JSONObject> pointById = new HashMap<>();
    private final Map<String, JSONObject> deviceOfPoint = new HashMap<>();
    /** The rooms in the family's order: home.json's, then those only the devices name. */
    final List<String> rooms = new ArrayList<>();

    private House(JSONObject house, JSONObject layout) {
        running = house != null;
        JSONArray list = house == null ? null : house.optJSONArray("devices");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject d = list.optJSONObject(i);
            devices.add(d);
            deviceById.put(d.optString("id"), d);
            JSONArray points = d.optJSONArray("points");
            for (int j = 0; points != null && j < points.length(); j++) {
                pointById.put(points.optJSONObject(j).optString("point"), points.optJSONObject(j));
                deviceOfPoint.put(points.optJSONObject(j).optString("point"), d);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        JSONArray written = layout == null ? null : layout.optJSONArray("rooms");
        for (int i = 0; written != null && i < written.length(); i++) {
            String n = written.optJSONObject(i) == null ? "" : written.optJSONObject(i).optString("name").trim();
            if (!n.isEmpty() && seen.add(Picker.fold(n))) rooms.add(n);
        }
        for (JSONObject d : devices) {
            String n = d.isNull("room") ? "" : d.optString("room").trim();
            if (!n.isEmpty() && seen.add(Picker.fold(n))) rooms.add(n);
        }
    }

    /** Reads it: the layout always, the devices if Moli answers. */
    static House read(Context context) throws Exception {
        JSONObject layout = Setup.home(context).optJSONObject("layout");
        JSONObject house = null;
        try { house = Setup.homeHouse(context); } catch (Exception silent) { /* Moli not answering */ }
        return new House(house, layout);
    }

    JSONObject device(String id) { return deviceById.get(id); }

    JSONObject point(String id) { return pointById.get(id); }

    static String deviceIdOf(String point) {
        int slash = point.lastIndexOf('/');
        return slash < 0 ? point : point.substring(0, slash);
    }

    String deviceName(String id) {
        JSONObject d = deviceById.get(id);
        return d == null ? id : d.optString("name", id);
    }

    String roomOf(String deviceId) {
        JSONObject d = deviceById.get(deviceId);
        return d == null || d.isNull("room") ? null : d.optString("room");
    }

    /** What a point holds now: « 19,5 °C », or "". */
    String now(String pointId) {
        JSONObject p = pointById.get(pointId);
        if (p == null || p.isNull("value")) return "";
        Object v = p.opt("value");
        String shown = v instanceof Number ? new java.text.DecimalFormat("0.#").format(((Number) v).doubleValue())
                : v instanceof Boolean ? ((Boolean) v ? "✓" : "✗") : String.valueOf(v);
        return p.isNull("unit") ? shown : shown + " " + p.optString("unit");
    }

    /** A point as the family reads it: « Sonde du bureau · Température · 19,5 °C ». */
    String pointWords(String pointId) {
        JSONObject p = pointById.get(pointId);
        if (p == null) return pointId;
        String v = now(pointId);
        return deviceName(deviceIdOf(pointId)) + " · " + p.optString("label") + (v.isEmpty() ? "" : " · " + v);
    }

    /** Whether Moli has this point (or device) now; true while Moli is not answering (nothing to say). */
    boolean has(String id) {
        return !running || pointById.containsKey(id) || deviceById.containsKey(id);
    }

    /** Every point to choose from, those `likely` first, in the picker's terms. */
    List<Picker.Item> pointItems(Predicate<JSONObject> likely, Predicate<JSONObject> shown) {
        List<Picker.Item> out = new ArrayList<>();
        for (JSONObject d : devices) {
            JSONArray points = d.optJSONArray("points");
            for (int j = 0; points != null && j < points.length(); j++) {
                JSONObject p = points.optJSONObject(j);
                if (shown != null && !shown.test(p)) continue;
                String v = now(p.optString("point"));
                out.add(new Picker.Item(p.optString("point"), d.optString("name") + " · " + p.optString("label"), v,
                        d.isNull("room") ? null : d.optString("room"), likely == null || likely.test(p)));
            }
        }
        return out;
    }

    /** Every device to choose from, those `likely` first. */
    List<Picker.Item> deviceItems(Predicate<JSONObject> likely, String detailKind) {
        List<Picker.Item> out = new ArrayList<>();
        for (JSONObject d : devices)
            out.add(new Picker.Item(d.optString("id"), d.optString("name"), detailKind == null ? "" : detailKind,
                    d.isNull("room") ? null : d.optString("room"), likely == null || likely.test(d)));
        return out;
    }

    /** A point's semantic is one of `semantics`. */
    static boolean semantic(JSONObject p, String... semantics) {
        String s = p.optString("semantic");
        for (String one : semantics) if (one.equals(s)) return true;
        return false;
    }

    /** A temperature in °C. */
    static boolean temperature(JSONObject p) {
        return "°C".equals(p.optString("unit")) || semantic(p, "temperature");
    }
}
