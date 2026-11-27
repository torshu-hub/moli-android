package io.moli.android;

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
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * « Accès et serveur »: `[server]`: the names Moli is reached by, the
 * house's time zone and language, how long history is kept, Cloudflare
 * Access, the mirror. Where it listens and where its data are belong to
 * the app on this tablet: shown, never changed here.
 */
public final class ServerActivity extends SettingsScreen {
    static final String[] ZONES = {"Europe/Paris", "Europe/Brussels", "Europe/Zurich", "Europe/Luxembourg", "America/Montreal"};
    static final String[] LANGUAGES = {"fr", "en"};
    static final int[] LANGUAGE_WORDS = {R.string.server_fr, R.string.server_en};

    private House house;
    private JSONObject s = new JSONObject();
    private String newHost = "";

    @Override protected int title() { return R.string.server_screen; }
    @Override protected int about() { return R.string.server_screen_about; }

    @Override protected void read() throws Exception {
        JSONObject sections = Setup.sections(this, "server").optJSONObject("sections");
        House seen = House.read(this);
        handler.post(() -> {
            house = seen;
            s = EnergyActivity.copy(sections.optJSONObject("server"));
        });
    }

    private void set(String key, Object value, Object fallback) {
        if (value == null || value.equals(fallback)) s.remove(key); else HeatingActivity.put(s, key, value);
    }

    @Override protected String draft() {
        try { return new JSONObject().put("server", s).toString(); } catch (JSONException impossible) { return null; }
    }

    @Override protected JSONObject preview(String draft) throws Exception { return Setup.sectionsPreview(this, new JSONObject(draft)); }

    @Override protected String write(String draft) throws Exception { return Setup.sectionsPut(this, new JSONObject(draft)); }

    @Override protected int saved() { return R.string.settings_saved_restarted; }

    private TextView label(int words) {
        TextView t = ui.text(getString(words), 14, Look.INK, true);
        t.setPadding(0, ui.dp(14), 0, ui.dp(6));
        return t;
    }

    @Override protected void fill(LinearLayout first, LinearLayout second) {
        first.addView(placeCard());
        first.addView(hostsCard());
        first.addView(historyCard());
        second.addView(accessCard());
        second.addView(tabletCard());
    }

    private View placeCard() {
        LinearLayout card = section(R.string.server_place, R.string.server_place_about);
        card.addView(label(R.string.server_timezone));
        String zone = s.optString("timezone");
        List<View> chips = new ArrayList<>();
        String tablet = TimeZone.getDefault().getID();
        List<String> zones = new ArrayList<>();
        zones.add(tablet);
        for (String z : ZONES) if (!zones.contains(z)) zones.add(z);
        if (!zone.isEmpty() && !zones.contains(zone)) zones.add(zone);
        for (String z : zones) chips.add(ui.chip(z, z.equals(zone), () -> { HeatingActivity.put(s, "timezone", z); relayout(); }));
        card.addView(ui.chips(chips));
        if (zone.isEmpty()) card.addView(warning(getString(R.string.server_timezone_missing)));
        card.addView(label(R.string.server_language));
        List<View> langs = new ArrayList<>();
        String language = s.optString("language", "fr");
        for (int i = 0; i < LANGUAGES.length; i++) {
            String l = LANGUAGES[i];
            langs.add(ui.chip(getString(LANGUAGE_WORDS[i]), l.equals(language), () -> { set("language", l, "fr"); relayout(); }));
        }
        card.addView(ui.chips(langs));
        return card;
    }

    private View hostsCard() {
        LinearLayout card = section(R.string.server_hosts, R.string.server_hosts_about);
        List<String> hosts = HomeActivity.strings(s.optJSONArray("allowed_hosts"));
        for (String h : hosts) {
            LinearLayout line = ui.row();
            line.addView(ui.text(h, 15, Look.INK, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> {
                hosts.remove(h);
                if (hosts.isEmpty()) s.remove("allowed_hosts"); else HeatingActivity.put(s, "allowed_hosts", new JSONArray(hosts));
                relayout();
            }));
            card.addView(line);
        }
        LinearLayout add = ui.row();
        EditText field = ui.field(getString(R.string.server_host_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(newHost);
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { newHost = e.toString(); }
        });
        add.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button plus = ui.flat(getString(R.string.home_room_alias_add));
        plus.setOnClickListener(v -> {
            String h = newHost.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("^https?://", "").replaceAll("[/:].*$", "");
            if (!h.isEmpty() && !hosts.contains(h)) { hosts.add(h); HeatingActivity.put(s, "allowed_hosts", new JSONArray(hosts)); }
            newHost = "";
            relayout();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44));
        p.setMargins(ui.dp(8), 0, 0, 0);
        add.addView(plus, p);
        card.addView(add, ui.matchWrap(0, ui.dp(6), 0, 0));
        return card;
    }

    private View historyCard() {
        LinearLayout card = section(R.string.server_history, R.string.server_history_about);
        card.addView(stepper(getString(R.string.server_history_days), s.has("history_days") ? (double) s.optInt("history_days") : null, 30, 5, 0, 3650,
                getString(R.string.server_days), v -> { set("history_days", v.intValue(), 30); changed(); }));
        List<String> hourly = HomeActivity.strings(s.optJSONArray("history_hourly"));
        List<String> names = new ArrayList<>();
        boolean gone = false;
        for (String p : hourly) { names.add(house.pointWords(p)); gone |= !house.has(p); }
        card.addView(chosen(getString(R.string.server_hourly), String.join("\n", names), gone ? getString(R.string.settings_point_gone) : null, () -> {
            if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
            Picker.show(this, getString(R.string.server_hourly),
                    house.pointItems(p -> p.optString("point").startsWith("dehors:") || House.temperature(p), p -> "numeric".equals(p.optString("type"))),
                    new HashSet<>(hourly), picked -> {
                        if (picked.isEmpty()) s.remove("history_hourly"); else HeatingActivity.put(s, "history_hourly", new JSONArray(picked));
                        relayout();
                    });
        }));
        card.addView(ui.note(getString(R.string.server_hourly_about)));
        return card;
    }

    private View accessCard() {
        LinearLayout card = section(R.string.server_access, R.string.server_access_about);
        JSONObject access = s.optJSONObject("access");
        Switch on = new Switch(this);
        on.setText(R.string.server_access_on);
        on.setTextColor(Look.INK);
        on.setTextSize(16);
        on.setChecked(access != null);
        on.setOnCheckedChangeListener((b, c) -> {
            if (c) HeatingActivity.put(s, "access", new JSONObject()); else s.remove("access");
            relayout();
        });
        card.addView(on);
        if (access == null) return card;
        card.addView(label(R.string.server_access_team));
        card.addView(text(access, "team", getString(R.string.server_access_team_hint)));
        card.addView(label(R.string.server_access_aud));
        card.addView(text(access, "aud", getString(R.string.server_access_aud_hint)));
        Switch known = new Switch(this);
        known.setText(R.string.server_access_known);
        known.setTextColor(Look.INK);
        known.setChecked(access.optBoolean("known_only"));
        known.setOnCheckedChangeListener((b, c) -> { if (c) HeatingActivity.put(access, "known_only", true); else access.remove("known_only"); changed(); });
        card.addView(known, ui.matchWrap(0, ui.dp(10), 0, 0));
        card.addView(ui.note(getString(R.string.server_access_known_about)));
        return card;
    }

    private EditText text(JSONObject into, String key, String hint) {
        EditText e = ui.field(hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(into.optString(key));
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable ed) {
                if (ed.toString().trim().isEmpty()) into.remove(key); else HeatingActivity.put(into, key, ed.toString().trim());
                changed();
            }
        });
        return e;
    }

    private View tabletCard() {
        LinearLayout card = section(R.string.server_tablet, R.string.server_tablet_about);
        card.addView(ui.text(getString(R.string.server_listen, s.optString("listen", "0.0.0.0:8790")), 14, Look.INK, false));
        card.addView(ui.text(getString(R.string.server_data, s.optString("data_dir", "—")), 14, Look.INK, false));
        card.addView(stepper(getString(R.string.server_workers), s.has("worker_threads") ? (double) s.optInt("worker_threads") : null, 2, 1, 1, 8, "",
                v -> { set("worker_threads", v.intValue(), 2); changed(); }));
        Switch mirror = new Switch(this);
        mirror.setText(R.string.server_mirror);
        mirror.setTextColor(Look.INK);
        mirror.setChecked(s.optBoolean("mirror"));
        mirror.setOnCheckedChangeListener((b, c) -> { set("mirror", c ? Boolean.TRUE : null, null); relayout(); });
        card.addView(mirror, ui.matchWrap(0, ui.dp(10), 0, 0));
        card.addView(s.optBoolean("mirror") ? warning(getString(R.string.server_mirror_on)) : ui.note(getString(R.string.server_mirror_about)));
        return card;
    }
}
