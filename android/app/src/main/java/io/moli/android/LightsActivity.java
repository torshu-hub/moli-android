package io.moli.android;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * « Lumières »: the house's light fixtures (`[[fixture]]`, D12): the bulbs
 * and the relay that feeds them, one light on the dashboard (`lumieres:<id>`).
 * Orders go to the bulbs; the relay only cuts when no bulb answers, and comes
 * back on its own when the light is switched on.
 */
public final class LightsActivity extends SettingsScreen {
    private House house;
    private List<JSONObject> fixtures = new ArrayList<>();

    @Override protected int title() { return R.string.lights_screen; }
    @Override protected int about() { return R.string.lights_screen_about; }

    @Override protected void read() throws Exception {
        JSONObject sections = Setup.sections(this, "fixture").optJSONObject("sections");
        House seen = House.read(this);
        handler.post(() -> {
            house = seen;
            fixtures = HomeActivity.objects(sections.optJSONArray("fixture"));
        });
    }

    @Override protected String draft() {
        try { return new JSONObject().put("fixture", new JSONArray(fixtures)).toString(); } catch (org.json.JSONException impossible) { return null; }
    }

    @Override protected JSONObject preview(String draft) throws Exception { return Setup.sectionsPreview(this, new JSONObject(draft)); }

    @Override protected String write(String draft) throws Exception { return Setup.sectionsPut(this, new JSONObject(draft)); }

    @Override protected int saved() { return R.string.settings_saved_restarted; }

    @Override protected void fill(LinearLayout first, LinearLayout second) {
        if (!house.running) {
            LinearLayout note = ui.card();
            note.addView(ui.note(getString(R.string.settings_no_moli)));
            first.addView(note);
        }
        LinearLayout card = section(R.string.lights_fixtures, R.string.lights_fixtures_about);
        for (int i = 0; i < fixtures.size(); i++) card.addView(fixtureView(i));
        if (fixtures.isEmpty()) card.addView(ui.note(getString(R.string.lights_none)));
        card.addView(addButton(R.string.lights_add, () -> edit(-1, null)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)));
        first.addView(card);
        View proposed = proposals();
        if (proposed != null) second.addView(proposed);
        LinearLayout why = section(R.string.lights_why, R.string.lights_why_about);
        second.addView(why);
    }

    /** Bulbs or relays already in a fixture (but `except`'s). */
    private Set<String> taken(int except) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < fixtures.size(); i++) {
            if (i == except) continue;
            out.addAll(HomeActivity.strings(fixtures.get(i).optJSONArray("bulbs")));
            if (!fixtures.get(i).optString("power").isEmpty()) out.add(fixtures.get(i).optString("power"));
        }
        return out;
    }

    private static boolean bulb(JSONObject d) {
        return "light".equals(d.optString("kind")) && !d.optString("id").startsWith("lumieres:") && !d.optString("id").startsWith("hue:group");
    }

    /**
     * Bulbs that do not answer now, room by room, none in a fixture: a wall
     * switch cuts them; with their relay they become one light that stays at hand.
     */
    private View proposals() {
        if (!house.running) return null;
        Set<String> taken = taken(-1);
        Map<String, List<String>> byRoom = new LinkedHashMap<>();
        for (JSONObject d : house.devices) {
            if (!bulb(d) || taken.contains(d.optString("id")) || !Boolean.FALSE.equals(d.opt("online"))) continue;
            String room = d.isNull("room") ? "" : d.optString("room");
            byRoom.computeIfAbsent(room, k -> new ArrayList<>()).add(d.optString("id"));
        }
        if (byRoom.isEmpty()) return null;
        LinearLayout card = ui.card();
        card.setBackground(ui.round(Look.BLUE[1], 14));
        card.addView(ui.text(getString(R.string.home_proposed), 16, Look.BLUE[0], true));
        card.addView(ui.note(getString(R.string.lights_proposed_about)));
        for (Map.Entry<String, List<String>> e : byRoom.entrySet()) {
            LinearLayout line = ui.row();
            List<String> names = new ArrayList<>();
            for (String id : e.getValue()) names.add(house.deviceName(id));
            LinearLayout words = ui.column();
            words.addView(ui.text(e.getKey().isEmpty() ? getString(R.string.picker_no_room) : e.getKey(), 15, Look.INK, true));
            words.addView(ui.text(String.join(", ", names), 13, Look.MUTED, false));
            line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button add = ui.flat(getString(R.string.home_proposed_add));
            add.setOnClickListener(v -> {
                JSONObject f = new JSONObject();
                HeatingActivity.put(f, "name", e.getKey().isEmpty() ? names.get(0) : e.getKey());
                if (!e.getKey().isEmpty()) HeatingActivity.put(f, "room", e.getKey());
                HeatingActivity.put(f, "bulbs", new JSONArray(e.getValue()));
                edit(-1, f);
            });
            line.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
            card.addView(line, ui.matchWrap(0, ui.dp(6), 0, 0));
        }
        return card;
    }

    private View fixtureView(int index) {
        JSONObject f = fixtures.get(index);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(14), ui.dp(12), ui.dp(8), ui.dp(12));
        LinearLayout head = ui.row();
        head.addView(ui.badge(R.drawable.ic_lightbulb, Look.AMBER));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(12), 0, 0, 0);
        words.addView(ui.text(f.optString("name"), 16, Look.INK, true));
        List<String> detail = new ArrayList<>();
        if (!f.optString("room").isEmpty()) detail.add(f.optString("room"));
        List<String> bulbs = HomeActivity.strings(f.optJSONArray("bulbs"));
        if (!bulbs.isEmpty()) detail.add(getResources().getQuantityString(R.plurals.lights_bulbs, bulbs.size(), bulbs.size()));
        if (!f.optString("power").isEmpty()) detail.add(getString(R.string.lights_with_relay));
        words.addView(ui.text(String.join("  ·  ", detail), 13, Look.MUTED, false));
        head.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> { fixtures.remove(index); relayout(); }));
        box.addView(head);
        boolean gone = false;
        for (String b : bulbs) gone |= !house.has(b);
        if (!f.optString("power").isEmpty()) gone |= !house.has(f.optString("power"));
        if (gone) box.addView(warning(getString(R.string.settings_point_gone)));
        ui.touchable(box, ui.stroke(Look.CARD, Look.LINE, 12));
        box.setOnClickListener(v -> edit(index, null));
        box.setLayoutParams(ui.matchWrap(0, ui.dp(6), 0, ui.dp(4)));
        return box;
    }

    /** A fixture's sheet; `index` -1: a new one, from `proposal` if given. */
    private void edit(int index, JSONObject proposal) {
        JSONObject f = EnergyActivity.copy(index >= 0 ? fixtures.get(index) : proposal);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8));
        LinearLayout body = ui.column();
        box.addView(body);
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            body.removeAllViews();
            body.addView(ui.text(getString(R.string.lights_name), 13, Look.MUTED, true));
            EditText name = ui.field(getString(R.string.lights_name_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            name.setText(f.optString("name"));
            name.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) { HeatingActivity.put(f, "name", e.toString().trim()); }
            });
            body.addView(name);

            TextView roomLabel = ui.text(getString(R.string.lights_room), 13, Look.MUTED, true);
            roomLabel.setPadding(0, ui.dp(14), 0, ui.dp(6));
            body.addView(roomLabel);
            List<View> rooms = new ArrayList<>();
            rooms.add(ui.chip(getString(R.string.lights_room_none), f.optString("room").isEmpty(), () -> { f.remove("room"); draw[0].run(); }));
            for (String r : house.rooms) rooms.add(ui.chip(r, r.equals(f.optString("room")), () -> { HeatingActivity.put(f, "room", r); draw[0].run(); }));
            body.addView(ui.chips(rooms));

            Set<String> others = taken(index);
            List<String> bulbs = HomeActivity.strings(f.optJSONArray("bulbs"));
            List<String> names = new ArrayList<>();
            for (String b : bulbs) names.add(house.deviceName(b));
            body.addView(chosen(getString(R.string.lights_bulbs_title), String.join(", ", names), null, () -> {
                if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
                List<Picker.Item> items = new ArrayList<>();
                for (Picker.Item i : house.deviceItems(LightsActivity::bulb, null)) if (!others.contains(i.id) && !i.id.startsWith("lumieres:")) items.add(i);
                Picker.show(this, getString(R.string.lights_bulbs_title), items, new HashSet<>(bulbs), picked -> {
                    HeatingActivity.put(f, "bulbs", new JSONArray(picked));
                    draw[0].run();
                });
            }));
            String power = f.optString("power");
            body.addView(chosen(getString(R.string.lights_relay), power.isEmpty() ? "" : house.pointWords(power), null, () -> {
                if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
                Set<String> now = new HashSet<>();
                if (!power.isEmpty()) now.add(power);
                List<Picker.Item> items = new ArrayList<>();
                for (Picker.Item i : house.pointItems(p -> House.semantic(p, "on_off") && !bulbs.contains(House.deviceIdOf(p.optString("point"))),
                        p -> House.semantic(p, "on_off") && p.optBoolean("writable")))
                    if (!others.contains(i.id) && !i.id.startsWith("lumieres:") && !bulbs.contains(House.deviceIdOf(i.id))) items.add(i);
                Picker.show(this, getString(R.string.lights_relay), items, now, picked -> {
                    String chosen = null;
                    for (String id : picked) if (!id.equals(power)) chosen = id;
                    if (chosen == null && !picked.isEmpty()) chosen = picked.get(0);
                    if (chosen == null) f.remove("power"); else HeatingActivity.put(f, "power", chosen);
                    draw[0].run();
                });
            }));
            body.addView(ui.note(getString(R.string.lights_relay_about)));
        };
        draw[0].run();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(index < 0 ? R.string.lights_add : R.string.lights_edit)
                .setView(ui.scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (f.optString("name").trim().isEmpty()) { banner(getString(R.string.home_room_name_missing), Look.WARN); return; }
            boolean bulbs = f.optJSONArray("bulbs") != null && f.optJSONArray("bulbs").length() > 0;
            if (!bulbs && f.optString("power").isEmpty()) {
                new AlertDialog.Builder(this).setMessage(R.string.lights_incomplete).setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            if (!bulbs) f.remove("bulbs");
            if (index < 0) {
                List<String> ids = new ArrayList<>();
                for (JSONObject x : fixtures) ids.add(x.optString("id"));
                String id = slug(f.optString("name"), ids, "luminaire");
                HeatingActivity.put(f, "id", id.length() > 32 ? id.substring(0, 32) : id);
                fixtures.add(f);
            } else fixtures.set(index, f);
            dialog.dismiss();
            relayout();
        }));
        dialog.show();
    }
}
