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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * « Chauffage »: the rooms Moli heats on their own sensor (`[[heating]]`)
 * and the house's programmes (`[heating_house]`), docs/components/pilotes.md
 * « Chauffage ». The weeks of each programme are set on the dashboard's
 * Chauffage page, not here.
 */
public final class HeatingActivity extends SettingsScreen {
    /** The official defaults (moli-heating `Room`), shown when the file says nothing. */
    static final double MIN = 5, MAX = 25, INITIAL = 19, HYSTERESIS = 0.3, MAX_BOOST = 3, FORCE = 4;
    static final int BOOST_MINUTES = 60, HOLD_MINUTES = 0;
    /** The setpoints of a valve, by their key. */
    static final Set<String> SETPOINTS = new HashSet<>(Arrays.asList("occupied_heating_setpoint", "current_heating_setpoint", "target_temperature"));

    private House house;
    private JSONObject written = new JSONObject();

    private List<JSONObject> rooms = new ArrayList<>();
    private boolean programmesOn;
    private List<String> programmes = new ArrayList<>();
    private String away, awayMode;
    private int awayDelay;

    @Override protected int title() { return R.string.heating_screen; }
    @Override protected int about() { return R.string.heating_screen_about; }

    @Override protected void read() throws Exception {
        JSONObject sections = Setup.sections(this, "heating", "heating_house").optJSONObject("sections");
        House seen = House.read(this);
        handler.post(() -> {
            written = sections;
            house = seen;
            rooms = HomeActivity.objects(sections.optJSONArray("heating"));
            JSONObject h = sections.optJSONObject("heating_house");
            programmesOn = h != null;
            programmes = h == null ? new ArrayList<>() : HomeActivity.strings(h.optJSONArray("programmes"));
            away = h == null || h.optString("away").isEmpty() ? null : h.optString("away");
            awayDelay = h == null ? 30 : h.optInt("away_delay", 30);
            awayMode = h == null ? "off" : h.optString("away_mode", "off");
        });
    }

    // ---- The draft ----

    private JSONObject houseSection() throws JSONException {
        if (!programmesOn) return null;
        JSONObject h = EnergyActivity.copy(written.optJSONObject("heating_house"));
        h.put("programmes", new JSONArray(programmes));
        if (away == null) { h.remove("away"); h.remove("away_delay"); } else { h.put("away", away); h.put("away_delay", awayDelay); }
        if ("off".equals(awayMode)) h.remove("away_mode"); else h.put("away_mode", awayMode);
        return h;
    }

    private JSONObject set(boolean all) throws JSONException {
        JSONObject set = new JSONObject();
        set.put("heating", new JSONArray(rooms));
        JSONObject h = houseSection();
        set.put("heating_house", h == null ? JSONObject.NULL : h);
        return set;
    }

    @Override protected String draft() {
        for (JSONObject r : rooms)
            if (r.optString("sensor").isEmpty() || r.optJSONArray("valves") == null || r.optJSONArray("valves").length() == 0) return null;
        if (programmesOn && programmes.isEmpty()) return null;
        try { return set(true).toString(); } catch (JSONException impossible) { return null; }
    }

    @Override protected JSONObject preview(String draft) throws Exception { return Setup.sectionsPreview(this, new JSONObject(draft)); }

    @Override protected String write(String draft) throws Exception { return Setup.sectionsPut(this, new JSONObject(draft)); }

    @Override protected int saved() { return R.string.heating_saved; }

    // ---- The page ----

    @Override protected void fill(LinearLayout first, LinearLayout second) {
        if (!house.running) {
            LinearLayout note = ui.card();
            note.addView(ui.note(getString(R.string.settings_no_moli)));
            first.addView(note);
        }
        first.addView(roomsCard());
        second.addView(programmesCard());
    }

    private Set<String> valvesUsed(int except) {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < rooms.size(); i++) {
            if (i == except) continue;
            JSONArray v = rooms.get(i).optJSONArray("valves");
            for (int j = 0; v != null && j < v.length(); j++) used.add(v.optString(j));
        }
        return used;
    }

    /** A valve's setpoint Moli may drive: not a Netatmo room (its own thermostat), not a heated room. */
    static boolean valve(JSONObject p) {
        String id = p.optString("point");
        return p.optBoolean("writable") && SETPOINTS.contains(id.substring(id.lastIndexOf('/') + 1))
                && !id.startsWith("netatmo:") && !id.startsWith("chauffage:");
    }

    /** A thermometer of the room: a temperature read, never a valve's own. */
    private boolean sensorLike(JSONObject p) {
        return House.temperature(p) && !p.optBoolean("writable") && !"local_temperature".equals(p.optString("point").replaceAll(".*/", ""))
                && !p.optString("point").startsWith("chauffage:");
    }

    /** The rooms that have valves no heated room drives yet, each with its valves and a sensor of the room. */
    private Map<String, JSONObject> proposals() {
        Map<String, JSONObject> out = new LinkedHashMap<>();
        if (!house.running) return out;
        Set<String> used = valvesUsed(-1);
        Set<String> heated = new HashSet<>();
        for (JSONObject r : rooms) heated.add(Picker.fold(r.optString("name")));
        for (String room : house.rooms) {
            if (heated.contains(Picker.fold(room))) continue;
            List<String> valves = new ArrayList<>();
            String sensor = null;
            for (JSONObject d : house.devices) {
                if (d.isNull("room") || !Picker.fold(d.optString("room")).equals(Picker.fold(room))) continue;
                JSONArray points = d.optJSONArray("points");
                boolean isValve = false;
                for (int j = 0; points != null && j < points.length(); j++) {
                    JSONObject p = points.optJSONObject(j);
                    if (valve(p) && !used.contains(p.optString("point"))) { valves.add(p.optString("point")); isValve = true; break; }
                }
                if (isValve) continue;
                for (int j = 0; sensor == null && points != null && j < points.length(); j++)
                    if (sensorLike(points.optJSONObject(j))) sensor = points.optJSONObject(j).optString("point");
            }
            if (valves.isEmpty() || sensor == null) continue;
            try {
                out.put(room, new JSONObject().put("name", room).put("sensor", sensor).put("valves", new JSONArray(valves)));
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        return out;
    }

    private List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (JSONObject r : rooms) ids.add(r.optString("id"));
        if (programmesOn) ids.add("maison");
        return ids;
    }

    private View roomsCard() {
        LinearLayout card = section(R.string.heating_rooms, R.string.heating_rooms_about);
        for (int i = 0; i < rooms.size(); i++) card.addView(roomView(i));
        if (rooms.isEmpty()) card.addView(ui.note(getString(R.string.heating_rooms_none)));
        card.addView(addButton(R.string.heating_room_add, () -> editRoom(-1, null)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)));
        Map<String, JSONObject> proposed = proposals();
        if (!proposed.isEmpty()) {
            LinearLayout box = ui.column();
            box.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
            box.setBackground(ui.round(Look.BLUE[1], 12));
            box.addView(ui.text(getString(R.string.home_proposed), 15, Look.BLUE[0], true));
            box.addView(ui.note(getString(R.string.heating_proposed_about)));
            for (Map.Entry<String, JSONObject> e : proposed.entrySet()) {
                LinearLayout line = ui.row();
                JSONObject p = e.getValue();
                LinearLayout words = ui.column();
                words.addView(ui.text(e.getKey(), 15, Look.INK, true));
                int n = p.optJSONArray("valves").length();
                words.addView(ui.text(getResources().getQuantityString(R.plurals.heating_valves, n, n) + " · " + house.pointWords(p.optString("sensor")), 13, Look.MUTED, false));
                line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button add = ui.flat(getString(R.string.home_proposed_add));
                add.setOnClickListener(v -> editRoom(-1, p));
                line.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
                box.addView(line, ui.matchWrap(0, ui.dp(6), 0, 0));
            }
            card.addView(box, ui.matchWrap(0, ui.dp(12), 0, 0));
        }
        return card;
    }

    private String regulationWords(JSONObject r) {
        String reg = r.optString("regulation", r.has("correction") && !r.optBoolean("correction", true) ? "none" : "shift");
        return getString("force".equals(reg) ? R.string.heating_reg_force : "none".equals(reg) ? R.string.heating_reg_none : R.string.heating_reg_shift);
    }

    private View roomView(int index) {
        JSONObject r = rooms.get(index);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(14), ui.dp(12), ui.dp(8), ui.dp(12));
        box.setBackground(ui.stroke(Look.CARD, Look.LINE, 12));
        LinearLayout head = ui.row();
        head.addView(ui.badge(R.drawable.ic_thermostat, Look.AMBER));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(12), 0, 0, 0);
        words.addView(ui.text(r.optString("name"), 16, Look.INK, true));
        words.addView(ui.text(regulationWords(r) + " · " + getString(R.string.heating_between,
                fmt(r.optDouble("min", MIN)), fmt(r.optDouble("max", MAX))), 13, Look.MUTED, false));
        head.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.heating_room_remove, () -> { rooms.remove(index); relayout(); }));
        box.addView(head);
        String sensor = r.optString("sensor");
        box.addView(detail(getString(R.string.heating_sensor), house.pointWords(sensor), house.has(sensor) ? null : getString(R.string.settings_point_gone)));
        JSONArray valves = r.optJSONArray("valves");
        List<String> names = new ArrayList<>();
        boolean gone = false;
        for (int j = 0; valves != null && j < valves.length(); j++) {
            names.add(house.deviceName(House.deviceIdOf(valves.optString(j))));
            gone |= !house.has(valves.optString(j));
        }
        box.addView(detail(getString(R.string.heating_valves_title), String.join(", ", names), gone ? getString(R.string.settings_point_gone) : null));
        boolean known = false;
        for (String room : house.rooms) known |= Picker.fold(room).equals(Picker.fold(r.optString("name")));
        if (house.running && !known) box.addView(warning(getString(R.string.heating_room_unknown)));
        ui.touchable(box, ui.stroke(Look.CARD, Look.LINE, 12));
        box.setOnClickListener(v -> editRoom(index, null));
        LinearLayout.LayoutParams p = ui.matchWrap(0, ui.dp(6), 0, ui.dp(4));
        box.setLayoutParams(p);
        return box;
    }

    private View detail(String label, String value, String warning) {
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(56), ui.dp(4), 0, 0);
        TextView t = ui.text(label + " : " + value, 14, Look.INK, false);
        box.addView(t);
        if (warning != null) box.addView(warning(warning));
        return box;
    }

    static String fmt(double v) { return new java.text.DecimalFormat("0.#").format(v); }

    /** A heated room's sheet. `index` -1: a new one, from `proposal` if given. */
    private void editRoom(int index, JSONObject proposal) {
        JSONObject r = index >= 0 ? EnergyActivity.copy(rooms.get(index)) : EnergyActivity.copy(proposal);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8));
        LinearLayout body = ui.column();
        box.addView(body);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            body.removeAllViews();
            // Its name: one of the house's rooms (the card goes there), or another.
            TextView nameLabel = ui.text(getString(R.string.heating_room_name), 13, Look.MUTED, true);
            body.addView(nameLabel);
            EditText name = ui.field(getString(R.string.heating_room_name), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            name.setText(r.optString("name"));
            name.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) { put(r, "name", e.toString().trim()); }
            });
            body.addView(name);
            List<View> chips = new ArrayList<>();
            for (String room : house.rooms) chips.add(ui.chip(room, Picker.fold(room).equals(Picker.fold(r.optString("name"))), () -> { put(r, "name", room); draw[0].run(); }));
            if (!chips.isEmpty()) body.addView(ui.chips(chips), ui.matchWrap(0, ui.dp(8), 0, 0));

            String sensor = r.optString("sensor");
            String room = r.optString("name");
            body.addView(chosen(getString(R.string.heating_sensor), sensor.isEmpty() ? "" : house.pointWords(sensor),
                    sensor.isEmpty() || house.has(sensor) ? null : getString(R.string.settings_point_gone), () -> {
                if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
                Set<String> now = new HashSet<>();
                if (!sensor.isEmpty()) now.add(sensor);
                Picker.show(this, getString(R.string.heating_sensor),
                        house.pointItems(p -> sensorLike(p) && sameRoom(p, room), p -> House.temperature(p) && !p.optBoolean("writable")),
                        now, picked -> {
                            // One sensor: the last ticked.
                            String chosen = null;
                            for (String id : picked) if (!id.equals(sensor)) chosen = id;
                            if (chosen == null && !picked.isEmpty()) chosen = picked.get(0);
                            put(r, "sensor", chosen == null ? "" : chosen);
                            draw[0].run();
                        });
            }));
            JSONArray valves = r.optJSONArray("valves");
            List<String> names = new ArrayList<>();
            for (int j = 0; valves != null && j < valves.length(); j++) names.add(house.pointWords(valves.optString(j)));
            body.addView(chosen(getString(R.string.heating_valves_title), String.join("\n", names), null, () -> {
                if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
                Set<String> now = new HashSet<>(HomeActivity.strings(valves));
                Set<String> others = valvesUsed(index);
                Picker.show(this, getString(R.string.heating_valves_title),
                        house.pointItems(p -> valve(p) && sameRoom(p, room) && !others.contains(p.optString("point")),
                                p -> p.optBoolean("writable") && House.temperature(p) && !p.optString("point").startsWith("chauffage:")),
                        now, picked -> { put(r, "valves", new JSONArray(picked)); draw[0].run(); });
            }));

            body.addView(label(R.string.heating_regulation));
            String reg = r.optString("regulation", r.has("correction") && !r.optBoolean("correction", true) ? "none" : "shift");
            List<View> regs = new ArrayList<>();
            String[] values = {"shift", "none", "force"};
            int[] words = {R.string.heating_reg_shift, R.string.heating_reg_none, R.string.heating_reg_force};
            for (int i = 0; i < values.length; i++) {
                String v = values[i];
                regs.add(ui.chip(getString(words[i]), v.equals(reg), () -> {
                    r.remove("correction");
                    if ("shift".equals(v)) r.remove("regulation"); else put(r, "regulation", v);
                    if (!"force".equals(v)) r.remove("force_offset");
                    draw[0].run();
                }));
            }
            body.addView(ui.chips(regs));
            body.addView(ui.note(getString("force".equals(reg) ? R.string.heating_reg_force_about
                    : "none".equals(reg) ? R.string.heating_reg_none_about : R.string.heating_reg_shift_about)));
            if ("force".equals(reg))
                body.addView(stepper(getString(R.string.heating_force_offset), opt(r, "force_offset"), FORCE, 0.5, 0.5, 10, "°C", v -> put(r, "force_offset", v)));

            body.addView(label(R.string.heating_fine));
            body.addView(stepper(getString(R.string.heating_initial), opt(r, "initial"), INITIAL, 0.5, 5, 30, "°C", v -> put(r, "initial", v)));
            body.addView(stepper(getString(R.string.heating_min), opt(r, "min"), MIN, 0.5, 5, 30, "°C", v -> put(r, "min", v)));
            body.addView(stepper(getString(R.string.heating_max), opt(r, "max"), MAX, 0.5, 5, 30, "°C", v -> put(r, "max", v)));
            body.addView(stepper(getString(R.string.heating_hysteresis), opt(r, "hysteresis"), HYSTERESIS, 0.1, 0, 2, "°C", v -> put(r, "hysteresis", Math.round(v * 10) / 10.0)));
            body.addView(stepper(getString(R.string.heating_max_boost), opt(r, "max_boost"), MAX_BOOST, 0.5, 0.5, 10, "°C", v -> put(r, "max_boost", v)));
            body.addView(stepper(getString(R.string.heating_boost_minutes), optInt(r, "boost_default_minutes"), BOOST_MINUTES, 15, 15, 720, "min", v -> put(r, "boost_default_minutes", v.intValue())));
            body.addView(stepper(getString(R.string.heating_hold_minutes), optInt(r, "hold_default_minutes"), HOLD_MINUTES, 15, 0, 720, "min", v -> put(r, "hold_default_minutes", v.intValue())));
            body.addView(ui.note(getString(R.string.heating_hold_about)));
        };
        draw[0].run();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(index < 0 ? R.string.heating_room_add : R.string.heating_room_edit)
                .setView(ui.scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (r.optString("name").trim().isEmpty()) { banner(getString(R.string.home_room_name_missing), Look.WARN); return; }
            if (r.optString("sensor").isEmpty() || r.optJSONArray("valves") == null || r.optJSONArray("valves").length() == 0) {
                new AlertDialog.Builder(this).setMessage(R.string.heating_incomplete).setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            if (index < 0) {
                List<String> taken = ids();
                put(r, "id", slug(r.optString("name"), taken, "piece"));
                rooms.add(r);
            } else rooms.set(index, r);
            dialog.dismiss();
            relayout();
        }));
        dialog.show();
    }

    private boolean sameRoom(JSONObject p, String room) {
        String r = house.roomOf(House.deviceIdOf(p.optString("point")));
        return r != null && Picker.fold(r).equals(Picker.fold(room));
    }

    static Double opt(JSONObject o, String key) { return o.has(key) ? o.optDouble(key) : null; }

    static Double optInt(JSONObject o, String key) { return o.has(key) ? (double) o.optInt(key) : null; }

    static void put(JSONObject o, String key, Object value) {
        try { o.put(key, value); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private TextView label(int words) {
        TextView t = ui.text(getString(words), 14, Look.INK, true);
        t.setPadding(0, ui.dp(16), 0, ui.dp(6));
        return t;
    }

    // ---- Programmes of the house ----

    private View programmesCard() {
        LinearLayout card = section(R.string.heating_programmes, R.string.heating_programmes_about);
        Switch on = new Switch(this);
        on.setText(R.string.heating_programmes_on);
        on.setTextSize(16);
        on.setTextColor(Look.INK);
        on.setChecked(programmesOn);
        on.setOnCheckedChangeListener((b, checked) -> {
            programmesOn = checked;
            if (checked && programmes.isEmpty()) programmes.add(getString(R.string.heating_programme_normal));
            relayout();
        });
        card.addView(on, ui.matchWrap(0, ui.dp(4), 0, ui.dp(4)));
        if (!programmesOn) return card;
        if (rooms.isEmpty()) card.addView(warning(getString(R.string.heating_programmes_no_room)));

        for (int i = 0; i < programmes.size(); i++) {
            int index = i;
            LinearLayout line = ui.row();
            LinearLayout words = ui.column();
            words.addView(ui.text(programmes.get(i), 16, Look.INK, true));
            if (i == 0) words.addView(ui.text(getString(R.string.heating_programme_first), 13, Look.MUTED, false));
            line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (i > 0) line.addView(iconButton(R.drawable.ic_arrow_upward, Look.MUTED, R.string.home_room_up, () -> { programmes.add(index - 1, programmes.remove(index)); relayout(); }));
            if (programmes.size() > 1) line.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> {
                String gone = programmes.remove(index);
                if (gone.equals(awayMode)) awayMode = "off";
                relayout();
            }));
            ui.touchable(line, ui.round(Look.CARD, 10));
            line.setOnClickListener(v -> renameProgramme(index));
            line.setPadding(0, ui.dp(4), 0, ui.dp(4));
            card.addView(line);
        }
        card.addView(addButton(R.string.heating_programme_add, () -> renameProgramme(-1)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)));
        card.addView(ui.note(getString(R.string.heating_programmes_weeks)));

        card.addView(label(R.string.heating_away));
        card.addView(chosen(getString(R.string.heating_away_point), away == null ? "" : house.pointWords(away),
                away != null && !house.has(away) ? getString(R.string.settings_point_gone) : null, () -> {
            if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
            Set<String> now = new HashSet<>();
            if (away != null) now.add(away);
            Picker.show(this, getString(R.string.heating_away_point),
                    house.pointItems(p -> p.optString("point").endsWith("/everyone_away"), p -> "binary".equals(p.optString("type")) && !p.optBoolean("writable")),
                    now, picked -> {
                        String chosen = null;
                        for (String id : picked) if (!id.equals(away)) chosen = id;
                        if (chosen == null && !picked.isEmpty()) chosen = picked.get(0);
                        away = chosen;
                        relayout();
                    });
        }));
        if (away != null) {
            card.addView(stepper(getString(R.string.heating_away_delay), (double) awayDelay, 30, 5, 0, 1440, "min", v -> { awayDelay = v.intValue(); changed(); }));
            card.addView(label(R.string.heating_away_mode));
            List<View> modes = new ArrayList<>();
            modes.add(ui.chip(getString(R.string.heating_frost), "off".equals(awayMode), () -> { awayMode = "off"; relayout(); }));
            for (String p : programmes) modes.add(ui.chip(p, p.equals(awayMode), () -> { awayMode = p; relayout(); }));
            card.addView(ui.chips(modes));
            Button none = ui.flat(getString(R.string.heating_away_none));
            none.setOnClickListener(v -> { away = null; relayout(); });
            card.addView(none, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        }
        return card;
    }

    private void renameProgramme(int index) {
        EditText field = ui.field(getString(R.string.heating_programme_name), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        String former = index < 0 ? "" : programmes.get(index);
        field.setText(former);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
        box.addView(field);
        if (index >= 0) box.addView(ui.note(getString(R.string.heating_programme_rename_about)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(index < 0 ? R.string.heating_programme_add : R.string.heating_programme_rename)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = field.getText().toString().trim();
            if (n.isEmpty()) { field.setError(getString(R.string.home_room_name_missing)); return; }
            for (int i = 0; i < programmes.size(); i++)
                if (i != index && Picker.fold(programmes.get(i)).replaceAll("[^a-z0-9]+", "").equals(Picker.fold(n).replaceAll("[^a-z0-9]+", ""))) {
                    field.setError(getString(R.string.heating_programme_twice));
                    return;
                }
            if (index < 0) programmes.add(n);
            else {
                programmes.set(index, n);
                if (former.equals(awayMode)) awayMode = n;
            }
            dialog.dismiss();
            relayout();
        }));
        dialog.show();
    }
}
