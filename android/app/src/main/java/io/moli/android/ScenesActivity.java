package io.moli.android;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
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
 * « Scènes et rythme »: the house's scenes (`[[scene]]`: lights of several
 * rooms set to a look in one gesture, and a few orders to the rest of the
 * house), which of them the dashboard's home page shows (`scenes` of
 * home.json), and the rhythm of the day (`[rhythm]`).
 */
public final class ScenesActivity extends SettingsScreen {
    /** The colours a light may take in a scene, warm to cool. */
    static final String[] COLORS = {"#ff7a00", "#ffb060", "#ff4d4d", "#ff66b3", "#b366ff", "#4d79ff", "#33ccff", "#33cc99", "#66cc33", "#ffe14d"};
    /** The default curve of the rhythm (moli-scenes `default_curve`). */
    static final String DEFAULT_CURVE = "[{\"at\":\"05:00\",\"white\":2200,\"brightness\":30},{\"at\":\"07:00\",\"white\":2700,\"brightness\":60},"
            + "{\"at\":\"09:00\",\"white\":4300,\"brightness\":100},{\"at\":\"16:00\",\"white\":4000,\"brightness\":100},"
            + "{\"at\":\"19:00\",\"white\":3000,\"brightness\":80},{\"at\":\"21:30\",\"white\":2400,\"brightness\":50},"
            + "{\"at\":\"23:30\",\"white\":2200,\"brightness\":30}]";

    private House house;
    private JSONArray writtenHome;
    private List<JSONObject> scenes = new ArrayList<>();
    /** Ids of the scenes the home page shows. */
    private final Set<String> shown = new HashSet<>();
    private boolean rhythmOn;
    private List<String> rhythmLights = new ArrayList<>();
    private List<JSONObject> curve = new ArrayList<>();
    /** moli.toml's part as written: unchanged, Moli is not restarted. */
    private String writtenSet = "";

    @Override protected int title() { return R.string.scenes_screen; }
    @Override protected int about() { return R.string.scenes_screen_about; }

    @Override protected void read() throws Exception {
        JSONObject sections = Setup.sections(this, "scene", "rhythm").optJSONObject("sections");
        JSONObject layout = Setup.home(this).optJSONObject("layout");
        House seen = House.read(this);
        handler.post(() -> {
            house = seen;
            scenes = HomeActivity.objects(sections.optJSONArray("scene"));
            writtenHome = layout == null ? null : layout.optJSONArray("scenes");
            shown.clear();
            if (writtenHome == null) for (JSONObject s : scenes) shown.add(s.optString("id"));
            else for (int i = 0; i < writtenHome.length(); i++) if (writtenHome.optJSONObject(i) != null) shown.add(writtenHome.optJSONObject(i).optString("id"));
            JSONObject rhythm = sections.optJSONObject("rhythm");
            rhythmOn = rhythm != null;
            rhythmLights = rhythm == null ? new ArrayList<>() : HomeActivity.strings(rhythm.optJSONArray("lights"));
            try {
                curve = HomeActivity.objects(rhythm != null && rhythm.has("curve") ? rhythm.optJSONArray("curve") : new JSONArray(DEFAULT_CURVE));
                writtenSet = set().toString();
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        });
    }

    // ---- The draft: moli.toml's part, and home.json's ----

    private JSONObject set() throws JSONException {
        JSONObject set = new JSONObject();
        set.put("scene", new JSONArray(scenes));
        if (!rhythmOn) set.put("rhythm", JSONObject.NULL);
        else {
            JSONObject r = new JSONObject().put("lights", new JSONArray(rhythmLights));
            JSONArray c = new JSONArray(curve);
            if (!c.toString().equals(new JSONArray(DEFAULT_CURVE).toString())) r.put("curve", c);
            set.put("rhythm", r);
        }
        return set;
    }

    /** The home page's scenes, in the scenes' order: each one's entry kept (icon, colours), a new one from its lights. */
    private JSONArray homeScenes() throws JSONException {
        JSONArray out = new JSONArray();
        for (JSONObject s : scenes) {
            if (!shown.contains(s.optString("id"))) continue;
            JSONObject entry = null;
            for (int i = 0; writtenHome != null && i < writtenHome.length(); i++)
                if (writtenHome.optJSONObject(i) != null && s.optString("id").equals(writtenHome.optJSONObject(i).optString("id")))
                    entry = EnergyActivity.copy(writtenHome.optJSONObject(i));
            if (entry == null) {
                entry = new JSONObject().put("id", s.optString("id"));
                JSONArray colors = colorsOf(s);
                if (colors.length() > 0) entry.put("colors", colors);
            }
            entry.put("name", s.optString("name"));
            out.put(entry);
        }
        return out;
    }

    /** Up to two colours a scene's pill takes from its lights (a white as its tint). */
    static JSONArray colorsOf(JSONObject scene) {
        JSONArray out = new JSONArray();
        JSONArray lights = scene.optJSONArray("lights");
        for (int i = 0; lights != null && i < lights.length() && out.length() < 2; i++) {
            JSONObject l = lights.optJSONObject(i);
            if (l == null || l.optBoolean("off")) continue;
            String c = l.has("color") ? l.optString("color") : l.has("white") ? kelvinColor(l.optInt("white")) : null;
            boolean twice = false;
            for (int j = 0; j < out.length(); j++) twice |= out.optString(j).equals(c);
            if (c != null && !twice) out.put(c);
        }
        return out;
    }

    /** A white's tint, roughly: 2200 K amber to 6500 K blue-white. */
    static String kelvinColor(int k) {
        double t = Math.max(0, Math.min(1, (k - 2200) / 4300.0));
        int r = 255, g = (int) (170 + 70 * t), b = (int) (90 + 160 * t);
        return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
    }

    @Override protected String draft() {
        if (rhythmOn && rhythmLights.isEmpty()) return null;
        for (JSONObject s : scenes) if (s.optJSONArray("lights") == null || s.optJSONArray("lights").length() == 0) return null;
        try { return new JSONObject().put("set", set()).put("home", homeScenes()).toString(); } catch (JSONException impossible) { return null; }
    }

    private boolean homeChanged(JSONArray home) {
        return writtenHome == null ? home.length() > 0 : !home.toString().equals(writtenHome.toString());
    }

    @Override protected JSONObject preview(String draft) throws Exception {
        JSONObject d = new JSONObject(draft);
        JSONObject toml = Setup.sectionsPreview(this, d.getJSONObject("set"));
        JSONArray home = d.getJSONArray("home");
        if (!homeChanged(home)) return toml;
        JSONObject json = Setup.homePreview(this, new JSONObject().put("scenes", home.length() == 0 ? JSONObject.NULL : home));
        String sep = "\n\n# home.json\n";
        return new JSONObject()
                .put("before", toml.optString("before") + sep + json.optString("before"))
                .put("after", toml.optString("after") + sep + json.optString("after"))
                .put("error", toml.isNull("error") ? json.opt("error") : toml.opt("error"));
    }

    @Override protected String write(String draft) throws Exception {
        JSONObject d = new JSONObject(draft);
        if (!d.getJSONObject("set").toString().equals(writtenSet)) {
            String refused = Setup.sectionsPut(this, d.getJSONObject("set"));
            if (refused != null) return refused;
            writtenSet = d.getJSONObject("set").toString();
        }
        JSONArray home = d.getJSONArray("home");
        if (!homeChanged(home)) return null;
        String refused = Setup.homePut(this, new JSONObject().put("scenes", home.length() == 0 ? JSONObject.NULL : home));
        if (refused == null) writtenHome = home.length() == 0 ? null : home;
        return refused;
    }

    // ---- The page ----

    @Override protected void fill(LinearLayout first, LinearLayout second) {
        if (!house.running) {
            LinearLayout note = ui.card();
            note.addView(ui.note(getString(R.string.settings_no_moli)));
            first.addView(note);
        }
        LinearLayout card = section(R.string.scenes_list, R.string.scenes_list_about);
        for (int i = 0; i < scenes.size(); i++) card.addView(sceneView(i));
        if (scenes.isEmpty()) card.addView(ui.note(getString(R.string.scenes_none)));
        card.addView(addButton(R.string.scenes_add, () -> editScene(-1)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)));
        first.addView(card);
        second.addView(rhythmCard());
    }

    private View swatch(String color) {
        View v = new View(this);
        int c;
        try { c = Color.parseColor(color); } catch (IllegalArgumentException bad) { c = Look.LINE; }
        v.setBackground(ui.round(c, 9));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18));
        p.setMargins(0, 0, ui.dp(4), 0);
        v.setLayoutParams(p);
        return v;
    }

    private View sceneView(int index) {
        JSONObject s = scenes.get(index);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(14), ui.dp(12), ui.dp(8), ui.dp(12));
        LinearLayout head = ui.row();
        head.addView(ui.badge(R.drawable.ic_palette, Look.AMBER));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(12), 0, 0, 0);
        LinearLayout titleLine = ui.row();
        titleLine.addView(ui.text(s.optString("name"), 16, Look.INK, true));
        JSONArray colors = colorsOf(s);
        LinearLayout dots = ui.row();
        dots.setPadding(ui.dp(10), 0, 0, 0);
        for (int i = 0; i < colors.length(); i++) dots.addView(swatch(colors.optString(i)));
        titleLine.addView(dots);
        words.addView(titleLine);
        int lights = s.optJSONArray("lights") == null ? 0 : s.optJSONArray("lights").length();
        int orders = s.optJSONArray("orders") == null ? 0 : s.optJSONArray("orders").length();
        List<String> detail = new ArrayList<>();
        detail.add(getResources().getQuantityString(R.plurals.scenes_lights, lights, lights));
        if (orders > 0) detail.add(getResources().getQuantityString(R.plurals.scenes_orders, orders, orders));
        if (s.optBoolean("cycle")) detail.add(getString(R.string.scenes_in_round));
        words.addView(ui.text(String.join("  ·  ", detail), 13, Look.MUTED, false));
        head.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (index > 0) head.addView(iconButton(R.drawable.ic_arrow_upward, Look.MUTED, R.string.home_room_up, () -> { scenes.add(index - 1, scenes.remove(index)); relayout(); }));
        head.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> { shown.remove(s.optString("id")); scenes.remove(index); relayout(); }));
        box.addView(head);
        Switch onHome = new Switch(this);
        onHome.setText(R.string.scenes_on_home);
        onHome.setTextColor(Look.INK);
        onHome.setChecked(shown.contains(s.optString("id")));
        onHome.setOnCheckedChangeListener((b, on) -> { if (on) shown.add(s.optString("id")); else shown.remove(s.optString("id")); changed(); });
        LinearLayout.LayoutParams sp = ui.matchWrap(ui.dp(56), ui.dp(6), 0, 0);
        box.addView(onHome, sp);
        if (gone(s)) box.addView(warning(getString(R.string.settings_point_gone)));
        ui.touchable(box, ui.stroke(Look.CARD, Look.LINE, 12));
        box.setOnClickListener(v -> editScene(index));
        box.setLayoutParams(ui.matchWrap(0, ui.dp(6), 0, ui.dp(4)));
        return box;
    }

    private boolean gone(JSONObject s) {
        JSONArray lights = s.optJSONArray("lights");
        for (int i = 0; lights != null && i < lights.length(); i++) if (!house.has(lights.optJSONObject(i).optString("id"))) return true;
        JSONArray orders = s.optJSONArray("orders");
        for (int i = 0; orders != null && i < orders.length(); i++) if (!house.has(orders.optJSONObject(i).optString("point"))) return true;
        return false;
    }

    /** What a light does in a scene, in words: « éteinte », « 2700 K · 40 % », « couleur · flamme ». */
    private String lookWords(JSONObject l) {
        if (l.optBoolean("off")) return getString(R.string.scenes_off);
        List<String> parts = new ArrayList<>();
        if (l.has("color")) parts.add(getString(R.string.scenes_color_word));
        if (l.has("white")) parts.add(l.optInt("white") + " K");
        if (l.has("brightness")) parts.add(HeatingActivity.fmt(l.optDouble("brightness")) + " %");
        if (l.has("effect")) parts.add(l.optString("effect"));
        return parts.isEmpty() ? getString(R.string.scenes_on) : String.join(" · ", parts);
    }

    private String orderWords(JSONObject o) {
        Object v = o.opt("value");
        String value = v instanceof Boolean ? getString((Boolean) v ? R.string.scenes_value_on : R.string.scenes_value_off) : String.valueOf(v);
        return house.pointWords(o.optString("point")).replaceAll(" · [^·]*$", "") + " → " + value;
    }

    private void editScene(int index) {
        JSONObject s = index >= 0 ? EnergyActivity.copy(scenes.get(index)) : new JSONObject();
        boolean[] onHome = {index < 0 || shown.contains(s.optString("id"))};
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8));
        LinearLayout body = ui.column();
        box.addView(body);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            body.removeAllViews();
            body.addView(ui.text(getString(R.string.scenes_name), 13, Look.MUTED, true));
            EditText name = ui.field(getString(R.string.scenes_name_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            name.setText(s.optString("name"));
            name.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence x, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) { HeatingActivity.put(s, "name", e.toString().trim()); }
            });
            body.addView(name);
            Switch cycle = new Switch(this);
            cycle.setText(R.string.scenes_cycle);
            cycle.setTextColor(Look.INK);
            cycle.setChecked(s.optBoolean("cycle"));
            cycle.setOnCheckedChangeListener((b, on) -> { if (on) HeatingActivity.put(s, "cycle", true); else s.remove("cycle"); });
            body.addView(cycle, ui.matchWrap(0, ui.dp(12), 0, 0));
            body.addView(ui.note(getString(R.string.scenes_cycle_about)));
            Switch home = new Switch(this);
            home.setText(R.string.scenes_on_home);
            home.setTextColor(Look.INK);
            home.setChecked(onHome[0]);
            home.setOnCheckedChangeListener((b, on) -> onHome[0] = on);
            body.addView(home, ui.matchWrap(0, ui.dp(4), 0, 0));

            TextView lightsHead = ui.text(getString(R.string.scenes_lights_title), 15, Look.INK, true);
            lightsHead.setPadding(0, ui.dp(16), 0, ui.dp(4));
            body.addView(lightsHead);
            JSONArray lights = s.optJSONArray("lights") == null ? new JSONArray() : s.optJSONArray("lights");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < lights.length(); i++) {
                JSONObject l = lights.optJSONObject(i);
                ids.add(l.optString("id"));
                int at = i;
                LinearLayout line = ui.row();
                LinearLayout w = ui.column();
                w.addView(ui.text(house.deviceName(l.optString("id")), 15, Look.INK, false));
                w.addView(ui.text(lookWords(l), 13, Look.MUTED, false));
                line.addView(w, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                if (l.has("color")) line.addView(swatch(l.optString("color")));
                line.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> { lights.remove(at); HeatingActivity.put(s, "lights", lights); draw[0].run(); }));
                ui.touchable(line, ui.round(Look.CARD, 10));
                line.setOnClickListener(v -> editLook(l, draw[0]));
                body.addView(line);
            }
            body.addView(addButton(R.string.scenes_lights_add, () -> {
                if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
                Picker.show(this, getString(R.string.scenes_lights_title),
                        house.deviceItems(d -> "light".equals(d.optString("kind")) || "switch".equals(d.optString("kind")), null),
                        new HashSet<>(ids), picked -> {
                            JSONArray next = new JSONArray();
                            for (int i = 0; i < lights.length(); i++) if (picked.contains(lights.optJSONObject(i).optString("id"))) next.put(lights.optJSONObject(i));
                            for (String id : picked) if (!ids.contains(id)) {
                                JSONObject l = new JSONObject();
                                HeatingActivity.put(l, "id", id);
                                next.put(l);
                            }
                            HeatingActivity.put(s, "lights", next);
                            draw[0].run();
                        });
            }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));

            TextView ordersHead = ui.text(getString(R.string.scenes_orders_title), 15, Look.INK, true);
            ordersHead.setPadding(0, ui.dp(16), 0, 0);
            body.addView(ordersHead);
            body.addView(ui.note(getString(R.string.scenes_orders_about)));
            JSONArray orders = s.optJSONArray("orders") == null ? new JSONArray() : s.optJSONArray("orders");
            for (int i = 0; i < orders.length(); i++) {
                int at = i;
                LinearLayout line = ui.row();
                line.addView(ui.text(orderWords(orders.optJSONObject(i)), 15, Look.INK, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                line.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> {
                    orders.remove(at);
                    if (orders.length() == 0) s.remove("orders"); else HeatingActivity.put(s, "orders", orders);
                    draw[0].run();
                }));
                body.addView(line);
            }
            body.addView(addButton(R.string.scenes_orders_add, () -> {
                if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
                Picker.show(this, getString(R.string.scenes_orders_title),
                        house.pointItems(p -> !"text".equals(p.optString("type")),
                                p -> p.optBoolean("writable") && !p.optString("point").startsWith("scenes:") && !"text".equals(p.optString("type"))),
                        new HashSet<>(), picked -> {
                            if (picked.isEmpty()) return;
                            chooseValue(picked.get(picked.size() - 1), value -> {
                                JSONObject o = new JSONObject();
                                HeatingActivity.put(o, "point", picked.get(picked.size() - 1));
                                HeatingActivity.put(o, "value", value);
                                orders.put(o);
                                HeatingActivity.put(s, "orders", orders);
                                draw[0].run();
                            });
                        });
            }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        };
        draw[0].run();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(index < 0 ? R.string.scenes_add : R.string.scenes_edit)
                .setView(ui.scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (s.optString("name").trim().isEmpty()) { banner(getString(R.string.home_room_name_missing), Look.WARN); return; }
            if (s.optJSONArray("lights") == null || s.optJSONArray("lights").length() == 0) {
                new AlertDialog.Builder(this).setMessage(R.string.scenes_incomplete).setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            if (index < 0) {
                List<String> ids = new ArrayList<>();
                ids.add("none"); ids.add("next"); ids.add("previous");
                for (JSONObject x : scenes) ids.add(x.optString("id"));
                HeatingActivity.put(s, "id", slug(s.optString("name"), ids, "scene"));
                scenes.add(s);
            } else scenes.set(index, s);
            if (onHome[0]) shown.add(s.optString("id")); else shown.remove(s.optString("id"));
            dialog.dismiss();
            relayout();
        }));
        dialog.show();
    }

    /** A light's look in a scene: off, or on with a white or a colour, a brightness, an animation. */
    private void editLook(JSONObject l, Runnable after) {
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8));
        LinearLayout body = ui.column();
        box.addView(body);
        JSONObject draft = EnergyActivity.copy(l);
        JSONObject effectPoint = house.point(l.optString("id") + "/effect");
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            body.removeAllViews();
            boolean off = draft.optBoolean("off");
            List<View> state = new ArrayList<>();
            state.add(ui.chip(getString(R.string.scenes_off), off, () -> {
                for (String k : new String[] {"color", "white", "brightness", "effect"}) draft.remove(k);
                HeatingActivity.put(draft, "off", true);
                draw[0].run();
            }));
            state.add(ui.chip(getString(R.string.scenes_on), !off, () -> { draft.remove("off"); draw[0].run(); }));
            body.addView(ui.chips(state));
            if (off) return;
            String tint = draft.has("color") ? "color" : draft.has("white") ? "white" : "same";
            List<View> tints = new ArrayList<>();
            tints.add(ui.chip(getString(R.string.scenes_same), "same".equals(tint), () -> { draft.remove("color"); draft.remove("white"); draw[0].run(); }));
            tints.add(ui.chip(getString(R.string.scenes_white), "white".equals(tint), () -> { draft.remove("color"); HeatingActivity.put(draft, "white", 2700); draw[0].run(); }));
            tints.add(ui.chip(getString(R.string.scenes_color_word), "color".equals(tint), () -> { draft.remove("white"); HeatingActivity.put(draft, "color", COLORS[1]); draw[0].run(); }));
            TextView t = ui.text(getString(R.string.scenes_tint), 13, Look.MUTED, true);
            t.setPadding(0, ui.dp(14), 0, ui.dp(6));
            body.addView(t);
            body.addView(ui.chips(tints));
            if ("white".equals(tint))
                body.addView(stepper(getString(R.string.scenes_white), (double) draft.optInt("white", 2700), 2700, 100, 1500, 6500, "K", v -> HeatingActivity.put(draft, "white", v.intValue())));
            if ("color".equals(tint)) {
                LinearLayout palette = ui.row();
                for (String c : COLORS) {
                    View dot = new View(this);
                    boolean on = c.equalsIgnoreCase(draft.optString("color"));
                    android.graphics.drawable.GradientDrawable g = ui.round(Color.parseColor(c), 18);
                    if (on) g.setStroke(ui.dp(3), Look.INK);
                    dot.setBackground(g);
                    dot.setOnClickListener(v -> { HeatingActivity.put(draft, "color", c); draw[0].run(); });
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36));
                    p.setMargins(0, ui.dp(8), ui.dp(8), 0);
                    palette.addView(dot, p);
                }
                android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(this);
                sc.addView(palette);
                body.addView(sc);
            }
            Switch level = new Switch(this);
            level.setText(R.string.scenes_brightness);
            level.setTextColor(Look.INK);
            level.setChecked(draft.has("brightness"));
            level.setOnCheckedChangeListener((b, on) -> { if (on) HeatingActivity.put(draft, "brightness", 50); else draft.remove("brightness"); draw[0].run(); });
            body.addView(level, ui.matchWrap(0, ui.dp(14), 0, 0));
            if (draft.has("brightness"))
                body.addView(stepper(getString(R.string.scenes_brightness), draft.optDouble("brightness"), 50, 5, 5, 100, "%", v -> HeatingActivity.put(draft, "brightness", v.intValue())));
            JSONArray effects = effectPoint == null || effectPoint.optJSONObject("kind") == null ? null : effectPoint.optJSONObject("kind").optJSONArray("values");
            if (effects != null || draft.has("effect")) {
                TextView e = ui.text(getString(R.string.scenes_effect), 13, Look.MUTED, true);
                e.setPadding(0, ui.dp(14), 0, ui.dp(6));
                body.addView(e);
                List<View> chips = new ArrayList<>();
                chips.add(ui.chip(getString(R.string.scenes_effect_none), !draft.has("effect"), () -> { draft.remove("effect"); draw[0].run(); }));
                Set<String> values = new java.util.LinkedHashSet<>();
                for (int i = 0; effects != null && i < effects.length(); i++) values.add(effects.optString(i));
                if (draft.has("effect")) values.add(draft.optString("effect"));
                for (String v : values) chips.add(ui.chip(v, v.equals(draft.optString("effect")), () -> { HeatingActivity.put(draft, "effect", v); draw[0].run(); }));
                body.addView(ui.chips(chips));
            }
        };
        draw[0].run();
        new AlertDialog.Builder(this)
                .setTitle(house.deviceName(l.optString("id")))
                .setView(ui.scroll(box))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    for (String k : new String[] {"off", "color", "white", "brightness", "effect"}) {
                        l.remove(k);
                        if (draft.has(k)) HeatingActivity.put(l, k, draft.opt(k));
                    }
                    after.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The value an order gives a point, chosen in its own terms (on/off, one of its values, a number). */
    private void chooseValue(String point, java.util.function.Consumer<Object> done) {
        JSONObject p = house.point(point);
        JSONObject kind = p == null ? null : p.optJSONObject("kind");
        String type = p == null ? "" : p.optString("type");
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        Object[] value = {null};
        AlertDialog[] dialog = new AlertDialog[1];
        if ("binary".equals(type)) {
            List<View> chips = new ArrayList<>();
            chips.add(ui.chip(getString(R.string.scenes_value_on), false, () -> { done.accept(true); dialog[0].dismiss(); }));
            chips.add(ui.chip(getString(R.string.scenes_value_off), false, () -> { done.accept(false); dialog[0].dismiss(); }));
            box.addView(ui.chips(chips));
        } else if ("enum".equals(type) && kind != null && kind.optJSONArray("values") != null) {
            JSONArray values = kind.optJSONArray("values");
            List<View> chips = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) {
                String v = values.optString(i);
                chips.add(ui.chip(v, false, () -> { done.accept(v); dialog[0].dismiss(); }));
            }
            LinearLayout wrap = ui.column();
            for (View c : chips) wrap.addView(c);
            box.addView(wrap);
        } else {
            double min = kind != null && kind.has("min") ? kind.optDouble("min") : 0, max = kind != null && kind.has("max") ? kind.optDouble("max") : 100;
            double step = kind != null && kind.has("step") ? kind.optDouble("step") : 1;
            value[0] = Math.max(min, Math.min(max, p != null && p.opt("value") instanceof Number ? ((Number) p.opt("value")).doubleValue() : min));
            box.addView(stepper(house.pointWords(point), (Double) value[0], min, step, min, max, p == null || p.isNull("unit") ? "" : p.optString("unit"), v -> value[0] = v));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(house.pointWords(point)).setView(box).setNegativeButton(android.R.string.cancel, null);
        if (value[0] != null) b.setPositiveButton(android.R.string.ok, (d, w) -> {
            double v = (Double) value[0];
            done.accept(v == Math.rint(v) ? (Object) (long) v : (Object) v);
        });
        dialog[0] = b.show();
    }

    // ---- The rhythm of the day ----

    private View rhythmCard() {
        LinearLayout card = section(R.string.scenes_rhythm, R.string.scenes_rhythm_about);
        Switch on = new Switch(this);
        on.setText(R.string.scenes_rhythm_on);
        on.setTextSize(16);
        on.setTextColor(Look.INK);
        on.setChecked(rhythmOn);
        on.setOnCheckedChangeListener((b, checked) -> { rhythmOn = checked; relayout(); });
        card.addView(on, ui.matchWrap(0, ui.dp(4), 0, ui.dp(4)));
        if (!rhythmOn) return card;
        List<String> names = new ArrayList<>();
        for (String id : rhythmLights) names.add(house.deviceName(id));
        card.addView(chosen(getString(R.string.scenes_rhythm_lights), String.join(", ", names), null, () -> {
            if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
            Picker.show(this, getString(R.string.scenes_rhythm_lights), house.deviceItems(d -> "light".equals(d.optString("kind")), null),
                    new HashSet<>(rhythmLights), picked -> { rhythmLights = new ArrayList<>(picked); relayout(); });
        }));
        TextView head = ui.text(getString(R.string.scenes_curve), 15, Look.INK, true);
        head.setPadding(0, ui.dp(12), 0, ui.dp(4));
        card.addView(head);
        for (int i = 0; i < curve.size(); i++) {
            int at = i;
            JSONObject stop = curve.get(i);
            LinearLayout line = ui.row();
            line.addView(swatch(kelvinColor(stop.optInt("white"))));
            line.addView(ui.text(stop.optString("at") + "    " + stop.optInt("white") + " K    " + HeatingActivity.fmt(stop.optDouble("brightness")) + " %", 15, Look.INK, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (curve.size() > 1) line.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> { curve.remove(at); relayout(); }));
            ui.touchable(line, ui.round(Look.CARD, 10));
            line.setOnClickListener(v -> editStop(at));
            card.addView(line);
        }
        LinearLayout actions = ui.row();
        actions.addView(addButton(R.string.scenes_curve_add, () -> editStop(-1)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        Button reset = ui.flat(getString(R.string.scenes_curve_default));
        reset.setOnClickListener(v -> {
            try { curve = HomeActivity.objects(new JSONArray(DEFAULT_CURVE)); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            relayout();
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40));
        rp.setMargins(ui.dp(8), 0, 0, 0);
        actions.addView(reset, rp);
        card.addView(actions, ui.matchWrap(0, ui.dp(6), 0, 0));
        card.addView(ui.note(getString(R.string.scenes_curve_about)));
        return card;
    }

    private void editStop(int index) {
        JSONObject stop = index >= 0 ? EnergyActivity.copy(curve.get(index)) : new JSONObject();
        if (index < 0) { HeatingActivity.put(stop, "at", "12:00"); HeatingActivity.put(stop, "white", 4000); HeatingActivity.put(stop, "brightness", 100); }
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        Button time = ui.flat(stop.optString("at"));
        time.setTextSize(20);
        time.setGravity(Gravity.CENTER);
        time.setOnClickListener(v -> {
            int m = EnergyActivity.minutes(stop.optString("at"));
            new TimePickerDialog(this, (picker, h, min) -> {
                HeatingActivity.put(stop, "at", EnergyActivity.clock(h * 60 + min));
                time.setText(stop.optString("at"));
            }, Math.max(0, m) / 60, Math.max(0, m) % 60, true).show();
        });
        box.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52)));
        box.addView(stepper(getString(R.string.scenes_white), (double) stop.optInt("white"), 2700, 100, 1500, 6500, "K", v -> HeatingActivity.put(stop, "white", v.intValue())));
        box.addView(stepper(getString(R.string.scenes_brightness), stop.optDouble("brightness"), 50, 5, 5, 100, "%", v -> HeatingActivity.put(stop, "brightness", v.intValue())));
        new AlertDialog.Builder(this)
                .setTitle(R.string.scenes_curve_stop)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (index >= 0) curve.remove(index);
                    // In the order of the day: Moli refuses a curve out of order.
                    int at = 0;
                    while (at < curve.size() && curve.get(at).optString("at").compareTo(stop.optString("at")) < 0) at++;
                    curve.add(at, stop);
                    relayout();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
