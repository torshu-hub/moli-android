package io.moli.android;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * « Dehors et soleil »: where the house is (`location` of home.json, which
 * wins over moli.toml), the weather outside (`[outdoor]`: the stations, then
 * the forecast, as `dehors:meteo`) and the façades the sun may be on
 * (`[sun]`, on `dehors:soleil`). docs/components/pilotes.md, `dehors`.
 */
public final class OutdoorActivity extends SettingsScreen {
    static final String[] AGES = {"15m", "30m", "1h", "2h", "6h"};
    static final int[] AGE_WORDS = {R.string.outdoor_15m, R.string.outdoor_30m, R.string.outdoor_1h, R.string.outdoor_2h, R.string.outdoor_6h};
    static final int[] DIRECTIONS = {R.string.outdoor_n, R.string.outdoor_ne, R.string.outdoor_e, R.string.outdoor_se,
            R.string.outdoor_s, R.string.outdoor_sw, R.string.outdoor_w, R.string.outdoor_nw};
    /** The sun's thresholds (moli-outdoor `sun`): on below `enter`, no longer above `exit`. */
    static final double ENTER = 62, EXIT = 75;

    private House house;
    private JSONObject writtenLocation;
    private JSONObject proposedPlace;
    private String writtenSet = "";

    private boolean outdoorOn, sunOn;
    private List<String> forecast = new ArrayList<>();
    private String forecastAge;
    private List<JSONObject> stations = new ArrayList<>();
    private Double enter, exit;
    private List<JSONObject> facades = new ArrayList<>();
    private String place = "", latitude = "", longitude = "";
    private JSONObject outdoorExtra = new JSONObject(), sunExtra = new JSONObject();

    @Override protected int title() { return R.string.outdoor_screen; }
    @Override protected int about() { return R.string.outdoor_screen_about; }

    @Override protected void read() throws Exception {
        JSONObject sections = Setup.sections(this, "outdoor", "sun", "automations").optJSONObject("sections");
        JSONObject layout = Setup.home(this).optJSONObject("layout");
        JSONArray drivers = Setup.drivers(this);
        House seen = House.read(this);
        handler.post(() -> {
            house = seen;
            JSONObject o = sections.optJSONObject("outdoor");
            outdoorOn = o != null;
            outdoorExtra = EnergyActivity.copy(o);
            Object f = o == null ? null : o.opt("forecast");
            forecast = f instanceof JSONArray ? HomeActivity.strings((JSONArray) f) : new ArrayList<>();
            if (f instanceof String) forecast.add((String) f);
            forecastAge = o == null || o.optString("forecast_stale_after").isEmpty() ? null : o.optString("forecast_stale_after");
            stations = HomeActivity.objects(o == null ? null : o.optJSONArray("station"));
            JSONObject s = sections.optJSONObject("sun");
            sunOn = s != null;
            sunExtra = EnergyActivity.copy(s);
            enter = s != null && s.has("enter") ? s.optDouble("enter") : null;
            exit = s != null && s.has("exit") ? s.optDouble("exit") : null;
            facades = HomeActivity.objects(s == null ? null : s.optJSONArray("facade"));
            writtenLocation = layout == null ? null : layout.optJSONObject("location");
            JSONObject a = sections.optJSONObject("automations");
            place = writtenLocation == null ? "" : writtenLocation.optString("place");
            latitude = writtenLocation != null ? plain(writtenLocation.opt("latitude")) : a != null && a.has("latitude") ? plain(a.opt("latitude")) : "";
            longitude = writtenLocation != null ? plain(writtenLocation.opt("longitude")) : a != null && a.has("longitude") ? plain(a.opt("longitude")) : "";
            // The weather integration already knows where the house is.
            proposedPlace = null;
            for (int i = 0; drivers != null && i < drivers.length(); i++) {
                JSONObject d = drivers.optJSONObject(i);
                JSONObject vars = d.optJSONObject("vars");
                if (vars != null && vars.has("lat") && vars.has("lon")) {
                    try { proposedPlace = new JSONObject().put("latitude", vars.optString("lat")).put("longitude", vars.optString("lon")); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
                    break;
                }
            }
            try { writtenSet = set().toString(); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        });
    }

    static String plain(Object v) { return v == null ? "" : v instanceof Number ? EnergyActivity.plain(v) : String.valueOf(v); }

    // ---- The draft ----

    private JSONObject set() throws JSONException {
        JSONObject set = new JSONObject();
        if (!outdoorOn) set.put("outdoor", JSONObject.NULL);
        else {
            JSONObject o = EnergyActivity.copy(outdoorExtra);
            if (forecast.isEmpty()) o.remove("forecast"); else o.put("forecast", forecast.size() == 1 ? forecast.get(0) : new JSONArray(forecast));
            if (forecastAge == null) o.remove("forecast_stale_after"); else o.put("forecast_stale_after", forecastAge);
            if (stations.isEmpty()) o.remove("station"); else o.put("station", new JSONArray(stations));
            set.put("outdoor", o);
        }
        if (!sunOn) set.put("sun", JSONObject.NULL);
        else {
            JSONObject s = EnergyActivity.copy(sunExtra);
            if (enter == null) s.remove("enter"); else s.put("enter", enter);
            if (exit == null) s.remove("exit"); else s.put("exit", exit);
            s.put("facade", new JSONArray(facades));
            set.put("sun", s);
        }
        return set;
    }

    /** The house's position as home.json keeps it; null: none (moli.toml speaks). */
    private JSONObject location() throws JSONException {
        Double lat = number(latitude), lon = number(longitude);
        if (lat == null || lon == null) return null;
        JSONObject l = writtenLocation == null ? new JSONObject() : EnergyActivity.copy(writtenLocation);
        l.put("latitude", lat).put("longitude", lon);
        if (place.trim().isEmpty()) l.remove("place"); else l.put("place", place.trim());
        return l;
    }

    static Double number(String text) {
        try { return text.trim().isEmpty() ? null : Double.valueOf(text.trim().replace(',', '.')); } catch (NumberFormatException bad) { return null; }
    }

    private boolean placeValid() {
        Double lat = number(latitude), lon = number(longitude);
        if (latitude.trim().isEmpty() && longitude.trim().isEmpty()) return true;
        return lat != null && lon != null && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    @Override protected String draft() {
        if (!placeValid()) return null;
        if (outdoorOn && forecast.isEmpty() && stations.isEmpty()) return null;
        if (sunOn && facades.isEmpty()) return null;
        try {
            JSONObject l = location();
            return new JSONObject().put("set", set()).put("location", l == null ? JSONObject.NULL : l).toString();
        } catch (JSONException impossible) { return null; }
    }

    private boolean locationChanged(JSONObject d) {
        Object now = d.opt("location");
        String before = writtenLocation == null ? "null" : writtenLocation.toString();
        return !String.valueOf(now).equals(before);
    }

    @Override protected JSONObject preview(String draft) throws Exception {
        JSONObject d = new JSONObject(draft);
        JSONObject toml = Setup.sectionsPreview(this, d.getJSONObject("set"));
        if (!locationChanged(d)) return toml;
        JSONObject json = Setup.homePreview(this, new JSONObject().put("location", d.opt("location")));
        String sep = "\n\n# home.json\n";
        return new JSONObject()
                .put("before", toml.optString("before") + sep + json.optString("before"))
                .put("after", toml.optString("after") + sep + json.optString("after"))
                .put("error", toml.isNull("error") ? json.opt("error") : toml.opt("error"));
    }

    @Override protected String write(String draft) throws Exception {
        JSONObject d = new JSONObject(draft);
        boolean moved = locationChanged(d);
        if (moved) {
            String refused = Setup.homePut(this, new JSONObject().put("location", d.opt("location")));
            if (refused != null) return refused;
            writtenLocation = d.optJSONObject("location");
        }
        String set = d.getJSONObject("set").toString();
        if (!set.equals(writtenSet)) {
            String refused = Setup.sectionsPut(this, d.getJSONObject("set"));
            if (refused != null) return refused;
            writtenSet = set;
        } else if (moved) {
            // Moli reads the position when it starts.
            Backup.stop(this);
            return Setup.startAndWait(this);
        }
        return null;
    }

    @Override protected int saved() { return R.string.settings_saved_restarted; }

    // ---- The page ----

    @Override protected void fill(LinearLayout first, LinearLayout second) {
        if (!house.running) {
            LinearLayout note = ui.card();
            note.addView(ui.note(getString(R.string.settings_no_moli)));
            first.addView(note);
        }
        first.addView(weatherCard());
        second.addView(placeCard());
        second.addView(sunCard());
    }

    private TextView label(int words) {
        TextView t = ui.text(getString(words), 14, Look.INK, true);
        t.setPadding(0, ui.dp(14), 0, ui.dp(6));
        return t;
    }

    private View placeCard() {
        LinearLayout card = section(R.string.outdoor_place, R.string.outdoor_place_about);
        EditText name = ui.field(getString(R.string.outdoor_place_name), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(place);
        name.addTextChangedListener(watcher(v -> place = v));
        card.addView(name, ui.matchWrap(0, ui.dp(6), 0, ui.dp(8)));
        LinearLayout both = ui.row();
        int numbers = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
        EditText lat = ui.field(getString(R.string.outdoor_latitude), numbers);
        lat.setText(latitude);
        lat.addTextChangedListener(watcher(v -> latitude = v));
        EditText lon = ui.field(getString(R.string.outdoor_longitude), numbers);
        lon.setText(longitude);
        lon.addTextChangedListener(watcher(v -> longitude = v));
        both.addView(lat, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(ui.dp(8), 0, 0, 0);
        both.addView(lon, p);
        card.addView(both);
        if (!placeValid()) card.addView(warning(getString(R.string.outdoor_place_invalid)));
        if (proposedPlace != null && !(proposedPlace.optString("latitude").equals(latitude) && proposedPlace.optString("longitude").equals(longitude))) {
            Button take = ui.flat(getString(R.string.outdoor_place_take));
            take.setOnClickListener(v -> { latitude = proposedPlace.optString("latitude"); longitude = proposedPlace.optString("longitude"); relayout(); });
            card.addView(take, ui.matchWrap(0, ui.dp(8), 0, 0));
        }
        return card;
    }

    private android.text.TextWatcher watcher(java.util.function.Consumer<String> set) {
        return new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { set.accept(e.toString()); changed(); }
        };
    }

    /** A forecast: the weather integrations' devices (Open-Meteo and its air quality). */
    private static boolean forecastLike(JSONObject d) {
        String id = d.optString("id");
        if (id.startsWith("meteo") || id.startsWith("air") || id.startsWith("dehors:")) return !id.startsWith("dehors:");
        JSONArray points = d.optJSONArray("points");
        for (int i = 0; points != null && i < points.length(); i++) {
            String key = points.optJSONObject(i).optString("point").replaceAll(".*/", "");
            if (key.equals("today_max") || key.equals("weather_code") || key.equals("forecast")) return true;
        }
        return false;
    }

    /** A station: a device that measures the weather (its driver says so), never a forecast. */
    private boolean stationLike(JSONObject d) {
        if (forecastLike(d) || d.optString("id").startsWith("dehors:")) return false;
        JSONArray points = d.optJSONArray("points");
        boolean temperature = false;
        for (int i = 0; points != null && i < points.length(); i++) {
            JSONObject p = points.optJSONObject(i);
            if (House.semantic(p, "wind_speed", "wind_gust", "rain_rate", "rain_today", "uv_index")) return true;
            temperature |= House.temperature(p) && !p.optBoolean("writable");
        }
        String room = d.isNull("room") ? "" : d.optString("room");
        return temperature && outsideRoom(room);
    }

    private boolean outsideRoom(String room) {
        String r = Picker.fold(room);
        return r.matches(".*\\b(exterieur|dehors|jardin|terrasse|balcon|allee|cour|garden|porch|outside)\\b.*");
    }

    private View weatherCard() {
        LinearLayout card = section(R.string.outdoor_weather, R.string.outdoor_weather_about);
        Switch on = new Switch(this);
        on.setText(R.string.outdoor_weather_on);
        on.setTextSize(16);
        on.setTextColor(Look.INK);
        on.setChecked(outdoorOn);
        on.setOnCheckedChangeListener((b, checked) -> {
            outdoorOn = checked;
            if (checked && forecast.isEmpty() && stations.isEmpty()) propose();
            relayout();
        });
        card.addView(on, ui.matchWrap(0, ui.dp(4), 0, ui.dp(4)));
        if (!outdoorOn) return card;

        card.addView(label(R.string.outdoor_stations));
        card.addView(ui.note(getString(R.string.outdoor_stations_about)));
        for (int i = 0; i < stations.size(); i++) {
            int at = i;
            JSONObject st = stations.get(i);
            LinearLayout line = ui.row();
            LinearLayout words = ui.column();
            String id = st.optString("device");
            words.addView(ui.text((i + 1) + ". " + house.deviceName(id) + (house.roomOf(id) == null ? "" : "  ·  " + house.roomOf(id)), 15, Look.INK, false));
            words.addView(ui.text(getString(R.string.outdoor_fresh_for, ageWords(st.optString("stale_after", null))), 13, Look.MUTED, false));
            if (!house.has(id)) words.addView(warning(getString(R.string.settings_point_gone)));
            line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (i > 0) line.addView(iconButton(R.drawable.ic_arrow_upward, Look.MUTED, R.string.home_room_up, () -> { stations.add(at - 1, stations.remove(at)); relayout(); }));
            line.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> { stations.remove(at); relayout(); }));
            ui.touchable(line, ui.round(Look.CARD, 10));
            line.setOnClickListener(v -> chooseAge(st.optString("stale_after", null), age -> {
                if (age == null) st.remove("stale_after"); else HeatingActivity.put(st, "stale_after", age);
                relayout();
            }));
            card.addView(line);
        }
        card.addView(addButton(R.string.outdoor_stations_add, () -> {
            if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
            Set<String> now = new HashSet<>();
            for (JSONObject st : stations) now.add(st.optString("device"));
            Picker.show(this, getString(R.string.outdoor_stations), house.deviceItems(this::stationLike, null), now, picked -> {
                stations.removeIf(st -> !picked.contains(st.optString("device")));
                for (String id : picked) if (!now.contains(id)) {
                    JSONObject st = new JSONObject();
                    HeatingActivity.put(st, "device", id);
                    stations.add(st);
                }
                relayout();
            });
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));

        card.addView(label(R.string.outdoor_forecast));
        List<String> names = new ArrayList<>();
        boolean gone = false;
        for (String id : forecast) { names.add(house.deviceName(id)); gone |= !house.has(id); }
        card.addView(chosen(getString(R.string.outdoor_forecast_title), String.join(", ", names), gone ? getString(R.string.settings_point_gone) : null, () -> {
            if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
            Picker.show(this, getString(R.string.outdoor_forecast_title), house.deviceItems(OutdoorActivity::forecastLike, null),
                    new HashSet<>(forecast), picked -> { forecast = new ArrayList<>(picked); relayout(); });
        }));
        LinearLayout age = ui.row();
        age.addView(ui.text(getString(R.string.outdoor_fresh_for, ageWords(forecastAge)), 14, Look.MUTED, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button change = ui.flat(getString(R.string.outdoor_change));
        change.setOnClickListener(v -> chooseAge(forecastAge, a -> { forecastAge = a; relayout(); }));
        age.addView(change, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        card.addView(age);
        if (forecast.isEmpty() && stations.isEmpty()) card.addView(warning(getString(R.string.outdoor_nothing)));
        return card;
    }

    /** Turned on with nothing chosen: the forecasts the house has, and its stations outside. */
    private void propose() {
        for (JSONObject d : house.devices) {
            if (forecastLike(d)) forecast.add(d.optString("id"));
            else if (stationLike(d)) {
                JSONObject st = new JSONObject();
                HeatingActivity.put(st, "device", d.optString("id"));
                stations.add(st);
            }
        }
        if (!forecast.isEmpty() || !stations.isEmpty()) banner(getString(R.string.outdoor_proposed), Look.INFO);
    }

    private String ageWords(String age) {
        for (int i = 0; i < AGES.length; i++) if (AGES[i].equals(age)) return getString(AGE_WORDS[i]);
        return age == null ? getString(R.string.outdoor_30m) : age;
    }

    private void chooseAge(String now, java.util.function.Consumer<String> done) {
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        box.addView(ui.note(getString(R.string.outdoor_age_about)));
        AlertDialog[] dialog = new AlertDialog[1];
        List<View> chips = new ArrayList<>();
        for (int i = 0; i < AGES.length; i++) {
            String a = AGES[i];
            boolean isDefault = "30m".equals(a);
            chips.add(ui.chip(getString(AGE_WORDS[i]), a.equals(now) || isDefault && now == null, () -> { done.accept(isDefault ? null : a); dialog[0].dismiss(); }));
        }
        box.addView(ui.chips(chips));
        dialog[0] = new AlertDialog.Builder(this).setTitle(R.string.outdoor_age).setView(box).setNegativeButton(android.R.string.cancel, null).show();
    }

    // ---- The sun on the façades ----

    private String direction(double azimuth) {
        return getString(DIRECTIONS[(int) Math.round(((azimuth % 360) + 360) % 360 / 45.0) % 8]);
    }

    private View sunCard() {
        LinearLayout card = section(R.string.outdoor_sun, R.string.outdoor_sun_about);
        Switch on = new Switch(this);
        on.setText(R.string.outdoor_sun_on);
        on.setTextSize(16);
        on.setTextColor(Look.INK);
        on.setChecked(sunOn);
        on.setOnCheckedChangeListener((b, checked) -> { sunOn = checked; relayout(); });
        card.addView(on, ui.matchWrap(0, ui.dp(4), 0, ui.dp(4)));
        if (!sunOn) return card;
        if (number(latitude) == null) card.addView(warning(getString(R.string.outdoor_sun_no_place)));
        for (int i = 0; i < facades.size(); i++) {
            int at = i;
            JSONObject f = facades.get(i);
            LinearLayout line = ui.row();
            LinearLayout words = ui.column();
            words.addView(ui.text(f.optString("name", f.optString("id")), 15, Look.INK, true));
            words.addView(ui.text(getString(R.string.outdoor_facing, direction(f.optDouble("azimuth")), HeatingActivity.fmt(f.optDouble("azimuth"))), 13, Look.MUTED, false));
            line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> { facades.remove(at); relayout(); }));
            ui.touchable(line, ui.round(Look.CARD, 10));
            line.setOnClickListener(v -> editFacade(at));
            card.addView(line);
        }
        card.addView(addButton(R.string.outdoor_facade_add, () -> editFacade(-1)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        card.addView(label(R.string.outdoor_thresholds));
        card.addView(ui.note(getString(R.string.outdoor_thresholds_about)));
        card.addView(stepper(getString(R.string.outdoor_enter), enter, ENTER, 1, 1, 89, "°", v -> { enter = v; changed(); }));
        card.addView(stepper(getString(R.string.outdoor_exit), exit, EXIT, 1, 2, 90, "°", v -> { exit = v; changed(); }));
        return card;
    }

    private void editFacade(int index) {
        JSONObject f = index >= 0 ? EnergyActivity.copy(facades.get(index)) : new JSONObject();
        if (index < 0) HeatingActivity.put(f, "azimuth", 180);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8));
        LinearLayout body = ui.column();
        box.addView(body);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            body.removeAllViews();
            EditText name = ui.field(getString(R.string.outdoor_facade_name), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            name.setText(f.optString("name"));
            name.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) { HeatingActivity.put(f, "name", e.toString().trim()); }
            });
            body.addView(name);
            body.addView(label(R.string.outdoor_facing_title));
            List<View> chips = new ArrayList<>();
            for (int d = 0; d < 8; d++) {
                int az = d * 45;
                chips.add(ui.chip(getString(DIRECTIONS[d]), Math.abs(f.optDouble("azimuth") - az) < 0.5, () -> { HeatingActivity.put(f, "azimuth", az); draw[0].run(); }));
            }
            body.addView(ui.chips(chips));
            body.addView(stepper(getString(R.string.outdoor_azimuth), f.optDouble("azimuth"), 180, 5, 0, 355, "°", v -> HeatingActivity.put(f, "azimuth", v.intValue())));
            body.addView(ui.note(getString(R.string.outdoor_azimuth_about)));
        };
        draw[0].run();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(index < 0 ? R.string.outdoor_facade_add : R.string.outdoor_facade_edit)
                .setView(ui.scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (f.optString("name").trim().isEmpty()) HeatingActivity.put(f, "name", direction(f.optDouble("azimuth")));
            if (index < 0) {
                List<String> ids = new ArrayList<>();
                for (JSONObject x : facades) ids.add(x.optString("id"));
                // An id of lower case letters only (moli-outdoor): « sud », « sud2 ».
                String base = Picker.fold(f.optString("name")).replaceAll("[^a-z]", "");
                if (base.isEmpty()) base = "facade";
                String id = base;
                for (int n = 2; ids.contains(id); n++) id = base + toLetters(n);
                HeatingActivity.put(f, "id", id.toLowerCase(Locale.ROOT));
                facades.add(f);
            } else facades.set(index, f);
            dialog.dismiss();
            relayout();
        }));
        dialog.show();
    }

    /** 2 → « b », 3 → « c »…: ids keep to letters. */
    static String toLetters(int n) {
        StringBuilder out = new StringBuilder();
        for (int v = n; v > 0; v /= 26) out.insert(0, (char) ('a' + (v - 1) % 26));
        return out.toString();
    }
}
