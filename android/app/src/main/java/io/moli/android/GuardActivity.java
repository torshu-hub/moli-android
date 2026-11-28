package io.moli.android;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * « Protection »: `[guard]`, where and when agents (Moli, a voice, MCP, a
 * script) must ask a person, and what wants the house's code. Edited by
 * people only, in the file: no surface of Moli can change it. Nothing here
 * protects anything by itself: the screen writes what the family chooses.
 */
public final class GuardActivity extends SettingsScreen {
    private House house;
    private JSONObject extra = new JSONObject();

    private boolean pinOnly, unassigned, quietOn;
    private List<String> rooms = new ArrayList<>(), devices = new ArrayList<>(), quietRooms = new ArrayList<>();
    private String from = "21:00", to = "07:30";
    private List<JSONObject> codeEachTime = new ArrayList<>(), agentsMay = new ArrayList<>();

    @Override protected int title() { return R.string.guard_screen; }
    @Override protected int about() { return R.string.guard_screen_about; }

    @Override protected void read() throws Exception {
        JSONObject sections = Setup.sections(this, "guard").optJSONObject("sections");
        House seen = House.read(this);
        handler.post(() -> {
            house = seen;
            JSONObject g = sections.optJSONObject("guard");
            extra = EnergyActivity.copy(g);
            pinOnly = g != null && "pin".equals(g.optString("dashboard"));
            unassigned = g != null && g.optBoolean("protect_unassigned");
            rooms = HomeActivity.strings(g == null ? null : g.optJSONArray("protected_rooms"));
            devices = HomeActivity.strings(g == null ? null : g.optJSONArray("protected_devices"));
            JSONObject q = g == null ? null : g.optJSONObject("quiet_hours");
            quietOn = q != null;
            from = q == null ? "21:00" : q.optString("from", "21:00");
            to = q == null ? "07:30" : q.optString("to", "07:30");
            quietRooms = HomeActivity.strings(q == null ? null : q.optJSONArray("rooms"));
            codeEachTime = HomeActivity.objects(g == null ? null : g.optJSONArray("code_each_time"));
            agentsMay = HomeActivity.objects(g == null ? null : g.optJSONArray("agents_may"));
        });
    }

    private JSONObject guard() throws JSONException {
        JSONObject g = EnergyActivity.copy(extra);
        if (pinOnly) g.put("dashboard", "pin"); else g.remove("dashboard");
        if (unassigned) g.put("protect_unassigned", true); else g.remove("protect_unassigned");
        if (rooms.isEmpty()) g.remove("protected_rooms"); else g.put("protected_rooms", new JSONArray(rooms));
        if (devices.isEmpty()) g.remove("protected_devices"); else g.put("protected_devices", new JSONArray(devices));
        if (!quietOn) g.remove("quiet_hours");
        else {
            JSONObject q = EnergyActivity.copy(extra.optJSONObject("quiet_hours"));
            q.put("from", from).put("to", to);
            if (quietRooms.isEmpty()) q.remove("rooms"); else q.put("rooms", new JSONArray(quietRooms));
            g.put("quiet_hours", q);
        }
        if (codeEachTime.isEmpty()) g.remove("code_each_time"); else g.put("code_each_time", new JSONArray(codeEachTime));
        if (agentsMay.isEmpty()) g.remove("agents_may"); else g.put("agents_may", new JSONArray(agentsMay));
        return g;
    }

    @Override protected String draft() {
        try {
            JSONObject g = guard();
            return new JSONObject().put("guard", g.length() == 0 ? JSONObject.NULL : g).toString();
        } catch (JSONException impossible) { return null; }
    }

    @Override protected JSONObject preview(String draft) throws Exception { return Setup.sectionsPreview(this, new JSONObject(draft)); }

    @Override protected String write(String draft) throws Exception { return Setup.sectionsPut(this, new JSONObject(draft)); }

    @Override protected int saved() { return R.string.settings_saved_restarted; }

    // ---- The page ----

    @Override protected void fill(LinearLayout first, LinearLayout second) {
        if (!house.running) {
            LinearLayout note = ui.card();
            note.addView(ui.note(getString(R.string.settings_no_moli)));
            first.addView(note);
        }
        first.addView(dashboardCard());
        first.addView(protectedCard());
        second.addView(quietCard());
        second.addView(ordersCard(R.string.guard_code_each_time, R.string.guard_code_each_time_about, codeEachTime));
        second.addView(ordersCard(R.string.guard_agents_may, R.string.guard_agents_may_about, agentsMay));
    }

    private View dashboardCard() {
        LinearLayout card = section(R.string.guard_dashboard, R.string.guard_dashboard_about);
        List<View> chips = new ArrayList<>();
        chips.add(ui.chip(getString(R.string.guard_trusted), !pinOnly, () -> { pinOnly = false; relayout(); }));
        chips.add(ui.chip(getString(R.string.guard_pin), pinOnly, () -> { pinOnly = true; relayout(); }));
        card.addView(ui.chips(chips));
        card.addView(ui.note(getString(pinOnly ? R.string.guard_pin_about : R.string.guard_trusted_about)));
        return card;
    }

    /** Rooms to tick: the house's, and any written that the house no longer names. */
    private View roomChips(List<String> chosen) {
        List<String> all = new ArrayList<>(house.rooms);
        for (String r : chosen) {
            boolean known = false;
            for (String a : all) known |= Picker.fold(a).equals(Picker.fold(r));
            if (!known) all.add(r);
        }
        List<View> chips = new ArrayList<>();
        for (String r : all) {
            boolean on = false;
            for (String c : chosen) on |= Picker.fold(c).equals(Picker.fold(r));
            boolean was = on;
            chips.add(ui.chip(r, on, () -> {
                if (was) chosen.removeIf(c -> Picker.fold(c).equals(Picker.fold(r))); else chosen.add(r);
                relayout();
            }));
        }
        if (chips.isEmpty()) return ui.note(getString(R.string.guard_no_rooms));
        // Wrapped in lines of four: every room in sight.
        LinearLayout box = ui.column();
        for (int i = 0; i < chips.size(); i += 4) box.addView(ui.chips(chips.subList(i, Math.min(chips.size(), i + 4))));
        return box;
    }

    private View protectedCard() {
        LinearLayout card = section(R.string.guard_protected, R.string.guard_protected_about);
        card.addView(roomChips(rooms));
        List<String> names = new ArrayList<>();
        boolean gone = false;
        for (String id : devices) { names.add(house.deviceName(id)); gone |= !house.has(id); }
        card.addView(chosen(getString(R.string.guard_devices), String.join(", ", names), gone ? getString(R.string.settings_point_gone) : null, () -> {
            if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
            Picker.show(this, getString(R.string.guard_devices), house.deviceItems(null, null), new HashSet<>(devices),
                    picked -> { devices = new ArrayList<>(picked); relayout(); });
        }));
        Switch none = new Switch(this);
        none.setText(R.string.guard_unassigned);
        none.setTextColor(Look.INK);
        none.setChecked(unassigned);
        none.setOnCheckedChangeListener((b, on) -> { unassigned = on; changed(); });
        card.addView(none, ui.matchWrap(0, ui.dp(8), 0, 0));
        return card;
    }

    private View quietCard() {
        LinearLayout card = section(R.string.guard_quiet, R.string.guard_quiet_about);
        Switch on = new Switch(this);
        on.setText(R.string.guard_quiet_on);
        on.setTextSize(16);
        on.setTextColor(Look.INK);
        on.setChecked(quietOn);
        on.setOnCheckedChangeListener((b, checked) -> { quietOn = checked; relayout(); });
        card.addView(on, ui.matchWrap(0, ui.dp(4), 0, ui.dp(4)));
        if (!quietOn) return card;
        LinearLayout times = ui.row();
        times.addView(time(R.string.guard_from, from, v -> from = v), new LinearLayout.LayoutParams(0, ui.dp(52), 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ui.dp(52), 1);
        p.setMargins(ui.dp(8), 0, 0, 0);
        times.addView(time(R.string.guard_to, to, v -> to = v), p);
        card.addView(times);
        TextView r = ui.text(getString(R.string.guard_quiet_rooms), 14, Look.INK, true);
        r.setPadding(0, ui.dp(14), 0, ui.dp(6));
        card.addView(r);
        card.addView(roomChips(quietRooms));
        if (quietRooms.isEmpty()) card.addView(ui.note(getString(R.string.guard_quiet_everywhere)));
        return card;
    }

    private Button time(int label, String value, java.util.function.Consumer<String> set) {
        Button b = ui.flat(getString(label, value));
        b.setOnClickListener(v -> {
            int m = Math.max(0, EnergyActivity.minutes(value));
            new TimePickerDialog(this, (picker, h, min) -> { set.accept(EnergyActivity.clock(h * 60 + min)); relayout(); }, m / 60, m % 60, true).show();
        });
        return b;
    }

    /** Orders on a point and some of its values (`code_each_time`, `agents_may`). */
    private View ordersCard(int title, int about, List<JSONObject> list) {
        LinearLayout card = section(title, about);
        for (int i = 0; i < list.size(); i++) {
            int at = i;
            JSONObject o = list.get(i);
            List<String> values = HomeActivity.strings(o.optJSONArray("values"));
            LinearLayout line = ui.row();
            LinearLayout words = ui.column();
            words.addView(ui.text(house.pointWords(o.optString("point")).replaceAll(" · [^·]*$", ""), 15, Look.INK, false));
            words.addView(ui.text(values.isEmpty() ? getString(R.string.guard_all_values) : String.join(", ", values), 13, Look.MUTED, false));
            if (!house.has(o.optString("point"))) words.addView(warning(getString(R.string.settings_point_gone)));
            line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> { list.remove(at); relayout(); }));
            ui.touchable(line, ui.round(Look.CARD, 10));
            line.setOnClickListener(v -> chooseValues(o, () -> relayout()));
            card.addView(line);
        }
        card.addView(addButton(R.string.guard_order_add, () -> {
            if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
            Picker.show(this, getString(title), house.pointItems(p -> !"binary".equals(p.optString("type")) || p.optBoolean("writable"), p -> p.optBoolean("writable")),
                    new HashSet<>(), picked -> {
                        if (picked.isEmpty()) return;
                        JSONObject o = new JSONObject();
                        HeatingActivity.put(o, "point", picked.get(picked.size() - 1));
                        chooseValues(o, () -> { list.add(o); relayout(); });
                    });
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        return card;
    }

    /** The values concerned, ticked among the point's (none: every value). */
    private void chooseValues(JSONObject o, Runnable after) {
        JSONObject p = house.point(o.optString("point"));
        JSONObject kind = p == null ? null : p.optJSONObject("kind");
        List<String> offered = new ArrayList<>();
        if (p != null && "binary".equals(p.optString("type"))) { offered.add("true"); offered.add("false"); }
        JSONArray values = kind == null ? null : kind.optJSONArray("values");
        for (int i = 0; values != null && i < values.length(); i++) offered.add(values.optString(i));
        List<String> chosen = HomeActivity.strings(o.optJSONArray("values"));
        for (String c : chosen) if (!offered.contains(c)) offered.add(c);
        if (offered.isEmpty()) { o.remove("values"); after.run(); return; }
        Set<String> ticked = new HashSet<>(chosen);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        box.addView(ui.note(getString(R.string.guard_values_about)));
        for (String v : offered) {
            android.widget.CheckBox c = new android.widget.CheckBox(this);
            c.setText("true".equals(v) ? getString(R.string.scenes_value_on) : "false".equals(v) ? getString(R.string.scenes_value_off) : v);
            c.setChecked(ticked.contains(v));
            c.setOnCheckedChangeListener((b, on) -> { if (on) ticked.add(v); else ticked.remove(v); });
            box.addView(c);
        }
        new AlertDialog.Builder(this)
                .setTitle(house.pointWords(o.optString("point")))
                .setView(ui.scroll(box))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<String> out = new ArrayList<>();
                    for (String v : offered) if (ticked.contains(v)) out.add(v);
                    if (out.isEmpty()) o.remove("values"); else HeatingActivity.put(o, "values", new JSONArray(out));
                    after.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
