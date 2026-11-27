package io.moli.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DecimalFormat;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The house's energy, set up from the tablet: the official `[energy]`
 * section of moli.toml (meters, driven loads, bill and tariff, live gauge,
 * battery), each in its own form, its points chosen among the house's.
 * Saving writes the whole section (energy.rs, held to the official check),
 * then restarts Moli as the integrations screen does. What the dashboard
 * already sets (the house's price per kWh) stays there.
 */
public final class EnergyActivity extends Activity {
    /** The roles of a meter (moli-energy `Role`), in the order offered. */
    static final String[] ROLES = {"appliance", "circuit", "total", "grid", "solar", "export", "gas", "charge", "discharge"};

    private final Look ui = new Look(this);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();

    /** The section as written in moli.toml (`{}` when the house has none). */
    private JSONObject energy = new JSONObject();
    /** The off-peak hours of the house (block `heures-creuses`), and the period they give (null: none). */
    private JSONArray offpeak = new JSONArray();
    private String offpeakPoint;
    static final String PEAK = "HP", OFF_PEAK = "HC";
    /** The house's points (null: Moli did not answer), and the plugs no meter follows. */
    private JSONArray points, suggested = new JSONArray();
    private final Map<String, JSONObject> pointById = new HashMap<>();
    private boolean wide, busy;
    private LinearLayout list, side;
    private View listPane, gap;
    private TextView banner;
    private Form form;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().getDecorView().setBackgroundColor(Look.PAGE);
        build();
        if (!Setup.config(this).exists() || !ProtectedKey.exists(this)) {
            banner.setText(R.string.setup_no_house);
            banner.setVisibility(View.VISIBLE);
            return;
        }
        load();
    }

    @Override public void onConfigurationChanged(Configuration changed) {
        super.onConfigurationChanged(changed);
        if ((changed.screenWidthDp >= 720) == wide) return;
        build();
        showList();
        showSide();
    }

    /** Two panes side by side on a tablet, one under the other on a phone. */
    private void build() {
        wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout root = ui.column();
        root.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0);
        banner = ui.text("", 15, android.graphics.Color.WHITE, false);
        banner.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        banner.setBackground(ui.round(Look.INFO, 10));
        banner.setVisibility(View.GONE);
        root.addView(banner, ui.matchWrap(0, 0, 0, ui.dp(10)));
        LinearLayout panes = new LinearLayout(this);
        panes.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        list = ui.column();
        side = ui.column();
        if (wide) {
            listPane = ui.scroll(list);
            panes.addView(listPane, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2));
            gap = new View(this);
            panes.addView(gap, new LinearLayout.LayoutParams(ui.dp(20), 1));
            panes.addView(ui.scroll(side), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3));
            root.addView(panes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            LinearLayout both = ui.column();
            listPane = list;
            gap = null;
            both.addView(list);
            both.addView(side, ui.matchWrap(0, ui.dp(16), 0, 0));
            root.addView(ui.scroll(both), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        setContentView(root);
    }

    // ---- Data ----

    /** Reads the section, then the house's points (Moli running). */
    private void load() {
        work.execute(() -> {
            try {
                JSONObject read = Setup.energy(this);
                JSONObject house = null;
                try { house = Setup.energyPoints(this); } catch (Exception silent) { /* Moli not answering: typed by hand */ }
                JSONObject points = house;
                handler.post(() -> {
                    if (isDestroyed()) return;
                    energy = read.optJSONObject("section") == null ? new JSONObject() : read.optJSONObject("section");
                    offpeak = read.optJSONArray("offpeak") == null ? new JSONArray() : read.optJSONArray("offpeak");
                    offpeakPoint = read.isNull("offpeak_point") ? null : read.optString("offpeak_point");
                    takePoints(points);
                    form = null;
                    showList();
                    showSide();
                    // Just restarted, Moli meets its devices as they answer: read them again.
                    handler.removeCallbacks(again);
                    handler.postDelayed(again, 10_000);
                });
            } catch (Exception failure) {
                handler.post(() -> {
                    banner.setText(getString(R.string.setup_failed, String.valueOf(failure.getMessage())));
                    banner.setBackground(ui.round(Look.BAD, 10));
                    banner.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void takePoints(JSONObject read) {
        points = read == null ? null : read.optJSONArray("points");
        suggested = read == null ? new JSONArray() : read.optJSONArray("suggested");
        pointById.clear();
        for (int i = 0; points != null && i < points.length(); i++)
            pointById.put(points.optJSONObject(i).optString("id"), points.optJSONObject(i));
    }

    /** The house's points read again; the list shown again unless a form is open. */
    private final Runnable again = () -> work.execute(() -> {
        JSONObject house;
        try { house = Setup.energyPoints(this); } catch (Exception silent) { return; }
        handler.post(() -> {
            if (isDestroyed() || busy) return;
            takePoints(house);
            if (form == null) showList();
        });
    });

    JSONArray array(String key) {
        JSONArray a = energy.optJSONArray(key);
        return a == null ? new JSONArray() : a;
    }

    static JSONObject copy(JSONObject o) {
        try { return new JSONObject(o == null ? "{}" : o.toString()); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** The section with `key` set to `value` (null, an empty list or table: taken out). */
    JSONObject with(String key, Object value) {
        JSONObject next = copy(energy);
        boolean empty = value == null
                || (value instanceof JSONArray && ((JSONArray) value).length() == 0)
                || (value instanceof JSONObject && ((JSONObject) value).length() == 0);
        try { if (empty) next.remove(key); else next.put(key, value); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return next;
    }

    /** The list `key` with entry `index` replaced (-1: added; `entry` null: removed). */
    JSONObject withEntry(String key, int index, JSONObject entry) {
        JSONArray all = new JSONArray();
        JSONArray now = array(key);
        for (int i = 0; i < now.length(); i++) {
            if (i != index) all.put(now.optJSONObject(i));
            else if (entry != null) all.put(entry);
        }
        if (index < 0 && entry != null) all.put(entry);
        return with(key, all);
    }

    /** An id from a name (`Télé du salon` → `tele_du_salon`), free in the list `key`. */
    String freeId(String key, String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "meter".equals(key) ? "compteur" : "charge";
        Set<String> taken = new HashSet<>();
        JSONArray all = array(key);
        for (int i = 0; i < all.length(); i++) taken.add(all.optJSONObject(i).optString("id"));
        String id = base;
        for (int n = 2; taken.contains(id); n++) id = base + "_" + n;
        return id;
    }

    // ---- Words ----

    String roleWords(String role) {
        int id = getResources().getIdentifier("energy_role_" + role, "string", getPackageName());
        return id == 0 ? role : getString(id);
    }

    /** A number to read (two decimals at most, the person's separator). */
    static String number(Object v) {
        if (!(v instanceof Number)) return null;
        return new DecimalFormat("0.##").format(((Number) v).doubleValue());
    }

    /** A number as written in moli.toml, every decimal kept (« 0.2016 »). */
    static String plain(Object v) {
        if (!(v instanceof Number)) return null;
        return java.math.BigDecimal.valueOf(((Number) v).doubleValue()).stripTrailingZeros().toPlainString();
    }

    /** A point as the family reads it: « Télé · Puissance · 52 W », else its id. */
    String pointWords(String id) {
        JSONObject p = pointById.get(id);
        if (p == null) return id;
        StringBuilder out = new StringBuilder(p.optString("name")).append(" · ").append(p.optString("label"));
        String now = number(p.opt("value"));
        if (now == null && p.opt("value") instanceof String) now = p.optString("value");
        if (now != null) out.append(" · ").append(now).append(p.isNull("unit") ? "" : " " + p.optString("unit"));
        return out.toString();
    }

    // ---- Left pane: the house's energy ----

    private void showList() {
        if (list == null) return;
        list.removeAllViews();
        list.addView(ui.heading(getString(R.string.energy)));
        if (points == null) list.addView(ui.note(getString(R.string.energy_no_points)));

        LinearLayout meters = ui.card();
        meters.addView(ui.title(getString(R.string.energy_meters)));
        JSONArray all = array("meter");
        if (all.length() == 0) meters.addView(ui.note(getString(R.string.energy_meters_empty)));
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            int at = i;
            meters.addView(entry(m.optString("name", m.optString("id")),
                    roleWords(m.optString("role")) + " · " + pointWords(m.optString("point")),
                    () -> open(new MeterForm(at, m))));
        }
        Button add = ui.flat(getString(R.string.energy_meter_add));
        add.setOnClickListener(v -> open(new MeterForm(-1, null)));
        meters.addView(add, ui.matchWrap(0, ui.dp(8), 0, 0));
        list.addView(meters);

        if (suggested.length() > 0) {
            LinearLayout plugs = ui.card();
            plugs.addView(ui.title(getString(R.string.energy_suggested)));
            plugs.addView(ui.note(getString(R.string.energy_suggested_help)));
            for (int i = 0; i < suggested.length(); i++) plugs.addView(suggestion(suggested.optJSONObject(i)));
            list.addView(plugs);
        }

        LinearLayout loads = ui.card();
        loads.addView(ui.title(getString(R.string.energy_loads)));
        JSONArray driven = array("load");
        if (driven.length() == 0) loads.addView(ui.note(getString(R.string.energy_loads_empty)));
        for (int i = 0; i < driven.length(); i++) {
            JSONObject l = driven.optJSONObject(i);
            int at = i;
            String power = number(l.opt("power_w"));
            loads.addView(entry(l.optString("name", l.optString("id")),
                    pointWords(l.optString("switch")) + (power == null ? "" : " · " + power + " W"),
                    () -> open(new LoadForm(at, l))));
        }
        Button addLoad = ui.flat(getString(R.string.energy_load_add));
        addLoad.setOnClickListener(v -> open(new LoadForm(-1, null)));
        loads.addView(addLoad, ui.matchWrap(0, ui.dp(8), 0, 0));
        list.addView(loads);

        LinearLayout more = ui.card();
        more.addView(ui.title(getString(R.string.energy_house)));
        more.addView(entry(getString(R.string.energy_bill), billWords(), () -> open(new BillForm())));
        JSONObject live = energy.optJSONObject("live");
        more.addView(entry(getString(R.string.energy_live),
                live == null ? getString(R.string.energy_not_set) : pointWords(live.optString("power")),
                () -> open(new LiveForm())));
        JSONObject battery = energy.optJSONObject("battery");
        more.addView(entry(getString(R.string.energy_battery),
                battery == null ? getString(R.string.energy_not_set)
                        : battery.has("soc") ? pointWords(battery.optString("soc")) : pointWords(battery.optString("power")),
                () -> open(new BatteryForm())));
        list.addView(more);

        Button page = ui.flat(getString(R.string.energy_open_page));
        page.setOnClickListener(v -> openDashboard());
        list.addView(page, ui.matchWrap(0, ui.dp(4), 0, 0));
    }

    private String billWords() {
        List<String> parts = new ArrayList<>();
        String fee = number(energy.opt("monthly_fee"));
        if (fee != null) parts.add(getString(R.string.energy_fee_words, fee, energy.optString("currency", "€")));
        JSONObject tariff = energy.optJSONObject("tariff");
        JSONObject prices = tariff == null ? null : tariff.optJSONObject("prices");
        if (prices != null) {
            List<String> each = new ArrayList<>();
            for (java.util.Iterator<String> k = prices.keys(); k.hasNext(); ) {
                String name = k.next();
                each.add(name + " " + plain(prices.opt(name)));
            }
            parts.add(String.join(", ", each));
        }
        if (offpeak.length() > 0) {
            List<String> each = new ArrayList<>();
            for (int i = 0; i < offpeak.length(); i++) each.add(offpeak.optString(i));
            parts.add(getString(R.string.energy_off_peak_words, String.join(", ", each)));
        }
        if (energy.has("budget")) parts.add(getString(R.string.energy_budget_words));
        return parts.isEmpty() ? getString(R.string.energy_not_set) : String.join(" · ", parts);
    }

    /** One line of a card: what it is, what it says, opens its form. */
    private View entry(String name, String words, Runnable open) {
        LinearLayout e = ui.column();
        e.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        e.setBackground(ui.stroke(Look.CARD, Look.LINE, 10));
        e.addView(ui.text(name, 15, Look.INK, true));
        e.addView(ui.text(words, 13, Look.MUTED, false));
        e.setOnClickListener(v -> { if (!busy) open.run(); });
        e.setLayoutParams(ui.matchWrap(0, ui.dp(6), 0, 0));
        return e;
    }

    /** A plug that measures, followed in one tap: an appliance whose energy Moli adds up. */
    private View suggestion(JSONObject s) {
        LinearLayout r = ui.row();
        r.setPadding(0, ui.dp(6), 0, ui.dp(6));
        TextView words = ui.text(s.optString("name") + "\n" + pointWords(s.optString("point")), 14, Look.INK, false);
        r.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button follow = ui.primary(getString(R.string.energy_follow));
        follow.setOnClickListener(v -> {
            if (busy) return;
            try {
                JSONObject meter = new JSONObject()
                        .put("id", freeId("meter", s.optString("name")))
                        .put("name", s.optString("name"))
                        .put("role", "appliance")
                        .put("point", s.optString("point"))
                        .put("integrate", true);
                apply(withEntry("meter", -1, meter));
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        });
        r.addView(follow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)));
        return r;
    }

    // ---- Right pane ----

    void open(Form f) { form = f; showList(); showSide(); }

    void closeForm() { form = null; showList(); showSide(); }

    private void showSide() {
        if (side == null) return;
        // An open form takes the whole screen; the list comes back with it closed.
        int shown = form == null ? View.VISIBLE : View.GONE;
        listPane.setVisibility(shown);
        if (gap != null) gap.setVisibility(shown);
        side.removeAllViews();
        for (android.view.ViewParent p = side.getParent(); p != null; p = p.getParent())
            if (p instanceof ScrollView) { ScrollView top = (ScrollView) p; top.post(() -> top.scrollTo(0, 0)); break; }
        if (form != null) { form.show(); return; }
        LinearLayout about = ui.card();
        about.addView(ui.title(getString(R.string.energy_about_title)));
        about.addView(ui.text(getString(R.string.energy_about), 14, Look.INK, false));
        about.addView(ui.note(getString(R.string.energy_price_elsewhere)));
        Button page = ui.flat(getString(R.string.energy_open_page));
        page.setOnClickListener(v -> openDashboard());
        about.addView(page, ui.matchWrap(0, ui.dp(8), 0, 0));
        side.addView(about);
    }

    void openDashboard() {
        startActivity(new Intent(this, DashboardActivity.class).putExtra(DashboardActivity.ROUTE, "#/energie"));
    }

    void apply(JSONObject section) { apply(section, null); }

    /** Writes the new section (and the off-peak hours) and restarts Moli, off the UI thread. */
    void apply(JSONObject section, JSONArray hours) {
        busy = true;
        banner.setText(R.string.setup_restarting);
        banner.setBackground(ui.round(Look.INFO, 10));
        banner.setVisibility(View.VISIBLE);
        if (form != null) form.lock(true);
        work.execute(() -> {
            String refused;
            try { refused = Setup.energyPut(this, section, hours); } catch (Exception failure) { refused = String.valueOf(failure.getMessage()); }
            String why = refused;
            handler.post(() -> {
                busy = false;
                if (isDestroyed()) return;
                if (why == null) {
                    banner.setText(R.string.setup_done);
                    banner.setBackground(ui.round(Look.OK, 10));
                    handler.postDelayed(() -> banner.setVisibility(View.GONE), 4000);
                    load();
                } else {
                    banner.setVisibility(View.GONE);
                    if (form != null) form.lock(false);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.setup_refused)
                            .setMessage(why)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            });
        });
    }

    @Override public void onBackPressed() {
        if (form != null && !busy) closeForm(); else super.onBackPressed();
    }

    @Override protected void onDestroy() { handler.removeCallbacks(again); work.shutdown(); super.onDestroy(); }

    // ---- Choosing a point ----

    /**
     * The house's points of these families (`power`, `energy`, `percent`,
     * `switch`, `number`, `text`), searchable; « all the points » for the
     * rest; `families` null: one line per device (what a meter feeds).
     * Without Moli, the point is typed.
     */
    void pick(String[] families, Consumer<JSONObject> chosen) {
        if (points == null || points.length() == 0) {
            Toast.makeText(this, R.string.energy_no_points, Toast.LENGTH_LONG).show();
            return;
        }
        List<String> wanted = families == null ? new ArrayList<>() : Arrays.asList(families);
        boolean devices = families == null;
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0);
        EditText search = ui.field(getString(R.string.energy_search), InputType.TYPE_CLASS_TEXT);
        box.addView(search, ui.matchWrap(0, 0, 0, ui.dp(6)));
        CheckBox every = new CheckBox(this);
        every.setText(R.string.energy_all_points);
        if (!devices) box.addView(every);
        ListView view = new ListView(this);
        box.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(360)));
        List<JSONObject> shown = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        view.setAdapter(adapter);
        Runnable fill = () -> {
            shown.clear();
            adapter.clear();
            String q = search.getText().toString().toLowerCase(Locale.ROOT).trim();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < points.length(); i++) {
                JSONObject p = points.optJSONObject(i);
                if (devices ? !seen.add(p.optString("device")) : !every.isChecked() && !wanted.contains(p.optString("family"))) continue;
                String words = devices ? p.optString("name") + " (" + p.optString("device") + ")" : pointWords(p.optString("id"));
                if (!q.isEmpty() && !(words + " " + p.optString("id")).toLowerCase(Locale.ROOT).contains(q)) continue;
                shown.add(p);
                adapter.add(words);
            }
            adapter.notifyDataSetChanged();
        };
        fill.run();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.energy_pick)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { fill.run(); }
        });
        every.setOnCheckedChangeListener((b, on) -> fill.run());
        view.setOnItemClickListener((parent, v, position, id) -> {
            chosen.accept(shown.get(position));
            dialog.dismiss();
        });
        dialog.show();
    }

    // ---- Forms ----

    /** A value the form cannot save: its field says why. */
    static final class Invalid extends Exception {
        Invalid() { super(null, null, false, false); }
    }

    /** One form: its fields, built in `fill`, read back in `section`. */
    abstract class Form {
        final List<View> controls = new ArrayList<>();
        LinearLayout panel;
        /** The off-peak hours to write (null: left as they are). */
        JSONArray hours;

        abstract String title();

        abstract void fill();

        /** The new section, or Invalid (the field says why). */
        abstract JSONObject section() throws Invalid, JSONException;

        /** The section without this item; null: nothing to remove. */
        JSONObject without() { return null; }

        void show() {
            LinearLayout top = ui.row();
            top.addView(ui.heading(title()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button close = ui.flat("✕");
            close.setOnClickListener(v -> closeForm());
            top.addView(close, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(44)));
            side.addView(top);
            panel = ui.card();
            side.addView(panel);
            fill();
            LinearLayout actions = ui.row();
            Button save = ui.primary(getString(R.string.setup_save));
            save.setOnClickListener(v -> {
                try { JSONObject next = section(); apply(next, hours); } catch (Invalid shownOnField) { /* the field says why */ } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            });
            actions.addView(save, ui.share(0));
            controls.add(save);
            JSONObject rest = without();
            if (rest != null) {
                Button remove = ui.danger(getString(R.string.setup_remove));
                remove.setOnClickListener(v -> apply(rest));
                actions.addView(remove, ui.share(ui.dp(12)));
                controls.add(remove);
            }
            panel.addView(actions, ui.matchWrap(0, ui.dp(18), 0, 0));
        }

        void lock(boolean on) { for (View v : controls) v.setEnabled(!on); }

        /** A box for one field (label, the field, its help), shown or hidden as a whole. */
        LinearLayout box(LinearLayout into, String label, String help, View field) {
            LinearLayout b = ui.column();
            if (label != null) b.addView(ui.text(label, 14, Look.INK, true), ui.matchWrap(0, ui.dp(12), 0, ui.dp(4)));
            b.addView(field, ui.matchWrap(0, label == null ? ui.dp(10) : 0, 0, 0));
            if (help != null) b.addView(ui.text(help, 12, Look.MUTED, false), ui.matchWrap(0, ui.dp(3), 0, 0));
            into.addView(b);
            controls.add(field);
            return b;
        }

        EditText words(LinearLayout into, int label, int help, String value, String hint) {
            EditText e = ui.field(hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setText(value);
            box(into, getString(label), help == 0 ? null : getString(help), e);
            return e;
        }

        EditText decimal(LinearLayout into, int label, int help, Object value, String hint) {
            EditText e = ui.field(hint, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            String now = plain(value);
            e.setText(now == null ? "" : now);
            box(into, getString(label), help == 0 ? null : getString(help), e);
            return e;
        }

        Switch toggle(LinearLayout into, int label, int help, boolean on) {
            Switch s = new Switch(EnergyActivity.this);
            s.setText(label);
            s.setTextSize(15);
            s.setTextColor(Look.INK);
            s.setChecked(on);
            box(into, null, help == 0 ? null : getString(help), s);
            return s;
        }

        /** A point: typed, or chosen among the house's; its words under it. */
        EditText point(LinearLayout into, int label, int help, String value, String[] families, Consumer<JSONObject> chosen) {
            LinearLayout line = ui.row();
            EditText e = ui.field(getString(R.string.energy_point_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setTypeface(Typeface.MONOSPACE);
            e.setTextSize(14);
            e.setText(value);
            line.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button choose = ui.flat(getString(R.string.energy_choose));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48));
            lp.setMargins(ui.dp(8), 0, 0, 0);
            line.addView(choose, lp);
            controls.add(choose);
            TextView said = ui.text("", 12, Look.ACCENT, false);
            Runnable say = () -> {
                String id = e.getText().toString().trim();
                said.setText(pointById.containsKey(id) ? pointWords(id) : "");
                said.setVisibility(said.getText().length() == 0 ? View.GONE : View.VISIBLE);
            };
            say.run();
            e.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { say.run(); }
            });
            choose.setOnClickListener(v -> pick(families, p -> {
                e.setText(p.optString("id"));
                e.setError(null);
                if (chosen != null) chosen.accept(p);
            }));
            LinearLayout b = box(into, getString(label), help == 0 ? null : getString(help), line);
            controls.add(e);
            b.addView(said, 2, ui.matchWrap(0, ui.dp(3), 0, 0));
            return e;
        }

        Spinner choice(LinearLayout into, int label, int help, String[] shown, int selected) {
            Spinner s = new Spinner(EnergyActivity.this);
            ArrayAdapter<String> a = new ArrayAdapter<>(EnergyActivity.this, android.R.layout.simple_spinner_dropdown_item, shown);
            s.setAdapter(a);
            s.setSelection(Math.max(0, selected));
            box(into, getString(label), help == 0 ? null : getString(help), s);
            return s;
        }

        /** A collapsed part, open if something in it is set. */
        LinearLayout more(boolean open) {
            LinearLayout part = ui.column();
            part.setVisibility(open ? View.VISIBLE : View.GONE);
            Button toggle = ui.flat(getString(R.string.setup_advanced));
            toggle.setOnClickListener(v -> part.setVisibility(part.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            panel.addView(toggle, ui.matchWrap(0, ui.dp(14), 0, 0));
            panel.addView(part);
            controls.add(toggle);
            return part;
        }

        // Reading the fields back.

        String text(EditText e) { return e.getText().toString().trim(); }

        String required(EditText e) throws Invalid {
            String t = text(e);
            if (t.isEmpty()) { e.setError(getString(R.string.energy_required)); e.requestFocus(); throw new Invalid(); }
            return t;
        }

        /** A point id as the official check wants it (`instance:device/key`), or empty. */
        String pointOf(EditText e, boolean needed) throws Invalid {
            String t = needed ? required(e) : text(e);
            if (!t.isEmpty() && !t.matches("[^:/\\s]+:[^/\\s]+/\\S+")) { e.setError(getString(R.string.energy_point_invalid)); e.requestFocus(); throw new Invalid(); }
            return t;
        }

        Double decimalOf(EditText e) throws Invalid {
            String t = text(e).replace(',', '.');
            if (t.isEmpty()) return null;
            try { return Double.parseDouble(t); } catch (NumberFormatException bad) { e.setError(getString(R.string.energy_number_invalid)); e.requestFocus(); throw new Invalid(); }
        }

        Integer wholeOf(EditText e) throws Invalid {
            String t = text(e);
            if (t.isEmpty()) return null;
            try { return Integer.parseInt(t); } catch (NumberFormatException bad) { e.setError(getString(R.string.energy_whole_invalid)); e.requestFocus(); throw new Invalid(); }
        }

        /** Sets `key` (null or empty: taken out). */
        void set(JSONObject o, String key, Object value) throws JSONException {
            if (value == null || "".equals(value) || Boolean.FALSE.equals(value)) o.remove(key); else o.put(key, value);
        }

        /** A field's box the role leaves shown (a folded « more » part still counts). */
        boolean on(View box) { return box.getVisibility() == View.VISIBLE; }
    }

    /** One meter (`[[energy.meter]]`): its fields by its role. */
    final class MeterForm extends Form {
        final int index;
        final JSONObject meter;
        EditText name, point, power, price, pricePoint, priceExtra, period, kwhPerUnit, feeds;
        Spinner role, within;
        Switch integrate, never, estimate, history;
        LinearLayout withinBox, estimateBox, integrateBox, powerBox, neverBox, priceBox, pricePointBox, priceExtraBox, periodBox, historyBox, kwhBox;
        final List<String> circuits = new ArrayList<>();

        MeterForm(int index, JSONObject meter) {
            this.index = index;
            this.meter = copy(meter);
        }

        @Override String title() { return index < 0 ? getString(R.string.energy_meter_new) : meter.optString("name"); }

        @Override void fill() {
            if (index >= 0) panel.addView(ui.note(getString(R.string.energy_id_fixed, meter.optString("id"))));
            name = words(panel, R.string.energy_name, 0, meter.optString("name"), getString(R.string.energy_name_hint));
            String[] roles = new String[ROLES.length];
            for (int i = 0; i < ROLES.length; i++) roles[i] = roleWords(ROLES[i]);
            role = choice(panel, R.string.energy_role, 0, roles, Arrays.asList(ROLES).indexOf(meter.optString("role", "appliance")));
            point = point(panel, R.string.energy_point, R.string.energy_point_help, meter.optString("point"),
                    new String[] {"power", "energy"}, p -> {
                        // A power is added up by Moli; a counter is read as it is.
                        integrate.setChecked("power".equals(p.optString("family")));
                        if (text(name).isEmpty()) name.setText(p.optString("name"));
                    });
            integrateBox = box(panel, null, getString(R.string.energy_integrate_help), integrate = toggle0(R.string.energy_integrate, meter.optBoolean("integrate")));

            circuits.add("");
            List<String> inside = new ArrayList<>();
            inside.add(getString(R.string.energy_within_none));
            JSONArray all = array("meter");
            for (int i = 0; i < all.length(); i++) {
                JSONObject m = all.optJSONObject(i);
                if (i == index || !("circuit".equals(m.optString("role")) || "total".equals(m.optString("role")))) continue;
                circuits.add(m.optString("id"));
                inside.add(m.optString("name"));
            }
            within = choice(panel, R.string.energy_within, R.string.energy_within_help, inside.toArray(new String[0]), circuits.indexOf(meter.optString("within")));
            withinBox = (LinearLayout) within.getParent();
            estimateBox = box(panel, null, getString(R.string.energy_estimate_help), estimate = toggle0(R.string.energy_estimate, meter.optBoolean("estimate")));
            price = decimal(panel, R.string.energy_price, R.string.energy_price_help, meter.opt("price"), "0.2016");
            priceBox = (LinearLayout) price.getParent();

            boolean advanced = meter.has("power") || meter.has("reset") || meter.has("period") || meter.has("price_point")
                    || meter.has("kwh_per_unit") || meter.has("history") || meter.has("feeds");
            LinearLayout part = more(advanced);
            power = point(part, R.string.energy_power, R.string.energy_power_help, meter.optString("power"), new String[] {"power"}, null);
            powerBox = parentBox(power);
            neverBox = box(part, null, getString(R.string.energy_never_help), never = toggle0(R.string.energy_never, "never".equals(meter.optString("reset"))));
            pricePoint = point(part, R.string.energy_price_point, R.string.energy_price_point_help, meter.optString("price_point"), new String[] {"number"}, null);
            pricePointBox = parentBox(pricePoint);
            priceExtra = decimal(part, R.string.energy_price_extra, R.string.energy_price_extra_help, meter.opt("price_extra"), "0.05");
            priceExtraBox = (LinearLayout) priceExtra.getParent();
            period = words(part, R.string.energy_period, R.string.energy_period_help, meter.optString("period"), "HC");
            periodBox = (LinearLayout) period.getParent();
            kwhPerUnit = decimal(part, R.string.energy_kwh_per_unit, R.string.energy_kwh_per_unit_help, meter.opt("kwh_per_unit"), "11.4");
            kwhBox = (LinearLayout) kwhPerUnit.getParent();
            historyBox = box(part, null, getString(R.string.energy_history_help), history = toggle0(R.string.energy_history, meter.optBoolean("history")));
            JSONArray fed = meter.optJSONArray("feeds");
            List<String> devices = new ArrayList<>();
            for (int i = 0; fed != null && i < fed.length(); i++) devices.add(fed.optString(i));
            feeds = words(part, R.string.energy_feeds, R.string.energy_feeds_help, String.join(", ", devices), "daikin:salon");
            Button addFeed = ui.flat(getString(R.string.energy_feeds_add));
            addFeed.setOnClickListener(v -> pick(null, p -> {
                String now = text(feeds);
                feeds.setText(now.isEmpty() ? p.optString("device") : now + ", " + p.optString("device"));
            }));
            part.addView(addFeed, ui.matchWrap(0, ui.dp(6), 0, 0));
            controls.add(addFeed);

            role.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) { follow(); }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            integrate.setOnCheckedChangeListener((b, on) -> follow());
            estimate.setOnCheckedChangeListener((b, on) -> follow());
            follow();
        }

        /** A switch not yet in a box (the box is made by the caller). */
        private Switch toggle0(int label, boolean on) {
            Switch s = new Switch(EnergyActivity.this);
            s.setText(label);
            s.setTextSize(15);
            s.setTextColor(Look.INK);
            s.setChecked(on);
            return s;
        }

        /** The box of a point field (its line is inside it). */
        private LinearLayout parentBox(EditText e) { return (LinearLayout) e.getParent().getParent(); }

        private String chosenRole() { return ROLES[Math.max(0, role.getSelectedItemPosition())]; }

        /** What each role takes (moli-energy `MeterConfig::check`). */
        private void follow() {
            String r = chosenRole();
            boolean appliance = "appliance".equals(r);
            boolean unpriced = "solar".equals(r) || "charge".equals(r) || "discharge".equals(r);
            boolean consumes = !(unpriced || "export".equals(r) || "gas".equals(r));
            boolean estimated = appliance && estimate.isChecked();
            boolean adds = integrate.isChecked() && !estimated;
            show(withinBox, appliance && circuits.size() > 1);
            show(estimateBox, appliance);
            show(integrateBox, !estimated);
            show(powerBox, !adds && !estimated);
            show(neverBox, !adds && !estimated);
            show(priceBox, !unpriced);
            show(pricePointBox, !unpriced);
            show(priceExtraBox, !unpriced);
            show(periodBox, consumes);
            show(kwhBox, !adds && !estimated);
            show(historyBox, (consumes || "gas".equals(r)) && !adds && !estimated);
        }

        private void show(View v, boolean on) { v.setVisibility(on ? View.VISIBLE : View.GONE); }

        @Override JSONObject section() throws Invalid, JSONException {
            JSONObject m = copy(meter);
            String n = required(name);
            m.put("name", n);
            if (index < 0) m.put("id", freeId("meter", n));
            m.put("role", chosenRole());
            m.put("point", pointOf(point, true));
            set(m, "integrate", on(integrateBox) && integrate.isChecked());
            set(m, "within", on(withinBox) ? circuits.get(Math.max(0, within.getSelectedItemPosition())) : null);
            set(m, "estimate", on(estimateBox) && estimate.isChecked());
            set(m, "price", on(priceBox) ? decimalOf(price) : null);
            set(m, "power", on(powerBox) ? pointOf(power, false) : null);
            set(m, "reset", on(neverBox) && never.isChecked() ? "never" : null);
            set(m, "price_point", on(pricePointBox) ? pointOf(pricePoint, false) : null);
            set(m, "price_extra", on(priceExtraBox) ? decimalOf(priceExtra) : null);
            set(m, "period", on(periodBox) ? text(period) : null);
            set(m, "kwh_per_unit", on(kwhBox) ? decimalOf(kwhPerUnit) : null);
            set(m, "history", on(historyBox) && history.isChecked());
            JSONArray devices = new JSONArray();
            for (String d : text(feeds).split("[,\\s]+")) if (!d.isEmpty()) devices.put(d);
            set(m, "feeds", devices.length() == 0 ? null : devices);
            return withEntry("meter", index, m);
        }

        @Override JSONObject without() { return index < 0 ? null : withEntry("meter", index, null); }
    }

    /** One driven load (`[[energy.load]]`): a water heater on a relay. */
    final class LoadForm extends Form {
        final int index;
        final JSONObject load;
        EditText name, relay, grid, power, offpeak, daily, minOn, minOff;

        LoadForm(int index, JSONObject load) {
            this.index = index;
            this.load = copy(load);
        }

        @Override String title() { return index < 0 ? getString(R.string.energy_load_new) : load.optString("name"); }

        @Override void fill() {
            panel.addView(ui.text(getString(R.string.energy_load_about), 14, Look.INK, false));
            if (index >= 0) panel.addView(ui.note(getString(R.string.energy_id_fixed, load.optString("id"))));
            name = words(panel, R.string.energy_name, 0, load.optString("name"), getString(R.string.energy_load_name_hint));
            relay = point(panel, R.string.energy_load_switch, R.string.energy_load_switch_help, load.optString("switch"), new String[] {"switch"},
                    p -> { if (text(name).isEmpty()) name.setText(p.optString("name")); });
            grid = point(panel, R.string.energy_load_grid, R.string.energy_load_grid_help, load.optString("grid"), new String[] {"power"}, null);
            power = decimal(panel, R.string.energy_load_power, 0, load.opt("power_w"), "2400");
            JSONArray stretches = load.optJSONArray("offpeak");
            List<String> each = new ArrayList<>();
            for (int i = 0; stretches != null && i < stretches.length(); i++) each.add(stretches.optString(i));
            offpeak = words(panel, R.string.energy_load_offpeak, R.string.energy_load_offpeak_help, String.join(", ", each), "22:00-06:00");
            daily = whole(panel, R.string.energy_load_daily, R.string.energy_load_daily_help, load.opt("daily_minutes"), "120");
            minOn = whole(panel, R.string.energy_load_min_on, 0, load.opt("min_on_minutes"), "10");
            minOff = whole(panel, R.string.energy_load_min_off, 0, load.opt("min_off_minutes"), "10");
        }

        private EditText whole(LinearLayout into, int label, int help, Object value, String hint) {
            EditText e = ui.field(hint, InputType.TYPE_CLASS_NUMBER);
            String now = plain(value);
            e.setText(now == null ? "" : now);
            box(into, getString(label), help == 0 ? null : getString(help), e);
            return e;
        }

        @Override JSONObject section() throws Invalid, JSONException {
            JSONObject l = copy(load);
            String n = required(name);
            l.put("name", n);
            if (index < 0) l.put("id", freeId("load", n));
            l.put("switch", pointOf(relay, true));
            l.put("grid", pointOf(grid, true));
            Double w = decimalOf(power);
            if (w == null) { required(power); }
            l.put("power_w", w);
            JSONArray stretches = new JSONArray();
            for (String s : text(offpeak).split("[,;\\s]+")) if (!s.isEmpty()) stretches.put(s);
            set(l, "offpeak", stretches.length() == 0 ? null : stretches);
            set(l, "daily_minutes", wholeOf(daily));
            set(l, "min_on_minutes", wholeOf(minOn));
            set(l, "min_off_minutes", wholeOf(minOff));
            return withEntry("load", index, l);
        }

        @Override JSONObject without() { return index < 0 ? null : withEntry("load", index, null); }
    }

    /**
     * The bill: subscription, budget, the price of peak and off-peak hours
     * and the off-peak hours of the contract (a block `heures-creuses` the
     * tariff follows), or the period a Linky reader gives; other periods
     * (Tempo), currency, the most a meter draws.
     */
    final class BillForm extends Form {
        EditText fee, budget, peak, offPeak, stretches, periodPoint, others, currency, maxPower;

        @Override String title() { return getString(R.string.energy_bill); }

        @Override void fill() {
            fee = decimal(panel, R.string.energy_fee, R.string.energy_fee_help, energy.opt("monthly_fee"), "19.56");
            budget = point(panel, R.string.energy_budget, R.string.energy_budget_help, energy.optString("budget"), new String[] {"number"}, null);
            JSONObject tariff = energy.optJSONObject("tariff");
            JSONObject each = tariff == null ? new JSONObject() : tariff.optJSONObject("prices");
            if (each == null) each = new JSONObject();
            panel.addView(ui.title(getString(R.string.energy_tariff)), ui.matchWrap(0, ui.dp(18), 0, 0));
            panel.addView(ui.note(getString(R.string.energy_tariff_help)));
            peak = decimal(panel, R.string.energy_price_peak, 0, each.opt(PEAK), "0.2081");
            offPeak = decimal(panel, R.string.energy_price_off_peak, 0, each.opt(OFF_PEAK), "0.1635");
            List<String> held = new ArrayList<>();
            for (int i = 0; i < offpeak.length(); i++) held.add(offpeak.optString(i));
            stretches = words(panel, R.string.energy_off_peak_hours, R.string.energy_off_peak_hours_help, String.join(", ", held), "22:00-06:00");
            // A period the hours give is theirs; another one is a meter's (Linky).
            String period = tariff == null ? "" : tariff.optString("period");
            if (period.equals(offpeakPoint)) period = "";
            StringBuilder lines = new StringBuilder();
            for (java.util.Iterator<String> k = each.keys(); k.hasNext(); ) {
                String name = k.next();
                if (name.equals(PEAK) || name.equals(OFF_PEAK)) continue;
                if (lines.length() > 0) lines.append('\n');
                lines.append(name).append(" = ").append(plain(each.opt(name)));
            }
            LinearLayout part = more(!period.isEmpty() || lines.length() > 0 || energy.has("currency") || energy.has("max_power_kw"));
            periodPoint = point(part, R.string.energy_tariff_period, R.string.energy_tariff_period_help, period, new String[] {"text"}, null);
            others = ui.field("BLEU_HP = 0.1609", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            others.setMinLines(2);
            others.setGravity(Gravity.TOP);
            others.setText(lines);
            box(part, getString(R.string.energy_tariff_prices), getString(R.string.energy_tariff_prices_help), others);
            currency = words(part, R.string.energy_currency, 0, energy.optString("currency"), "€");
            maxPower = decimal(part, R.string.energy_max_power, R.string.energy_max_power_help, energy.opt("max_power_kw"), "36");
            panel.addView(ui.note(getString(R.string.energy_price_elsewhere)), ui.matchWrap(0, ui.dp(12), 0, 0));
            Button page = ui.flat(getString(R.string.energy_open_page));
            page.setOnClickListener(v -> openDashboard());
            panel.addView(page);
        }

        @Override JSONObject section() throws Invalid, JSONException {
            JSONObject next = copy(energy);
            set(next, "monthly_fee", decimalOf(fee));
            set(next, "budget", pointOf(budget, false));
            set(next, "currency", text(currency));
            set(next, "max_power_kw", decimalOf(maxPower));
            JSONObject table = new JSONObject();
            set(table, PEAK, decimalOf(peak));
            set(table, OFF_PEAK, decimalOf(offPeak));
            for (String line : text(others).split("\n")) {
                String l = line.trim();
                if (l.isEmpty()) continue;
                String[] parts = l.split("\\s*[=:]\\s*|\\s+", 2);
                Double price = null;
                try { if (parts.length == 2) price = Double.parseDouble(parts[1].replace(',', '.').replace("€", "").trim()); } catch (NumberFormatException bad) { price = null; }
                if (price == null || parts[0].isEmpty()) { others.setError(getString(R.string.energy_tariff_prices_invalid, l)); others.requestFocus(); throw new Invalid(); }
                table.put(parts[0], price);
            }
            hours = new JSONArray();
            for (String h : text(stretches).split("[,;]")) if (!h.trim().isEmpty()) hours.put(h.trim());
            String period = pointOf(periodPoint, false);
            if (table.length() == 0) {
                if (hours.length() > 0 || !period.isEmpty()) { required(peak); }
                set(next, "tariff", null);
                return next;
            }
            if (hours.length() == 0 && period.isEmpty()) {
                stretches.setError(getString(R.string.energy_off_peak_hours_needed));
                stretches.requestFocus();
                throw new Invalid();
            }
            JSONObject tariff = copy(energy.optJSONObject("tariff"));
            tariff.put("prices", table);
            // Without a meter's period, Moli follows the hours (energy.rs).
            set(tariff, "period", period);
            next.put("tariff", tariff);
            return next;
        }
    }

    /** The live gauge: the supplier's meter now, against the contract. */
    final class LiveForm extends Form {
        EditText power, max, warn;

        @Override String title() { return getString(R.string.energy_live); }

        @Override void fill() {
            JSONObject live = energy.optJSONObject("live");
            if (live == null) live = new JSONObject();
            power = point(panel, R.string.energy_live_power, R.string.energy_live_power_help, live.optString("power"), new String[] {"power"}, null);
            max = decimal(panel, R.string.energy_live_max, R.string.energy_live_max_help, live.opt("max"), "9000");
            warn = decimal(panel, R.string.energy_live_warn, R.string.energy_live_warn_help, live.opt("warn"), "2000");
        }

        @Override JSONObject section() throws Invalid, JSONException {
            JSONObject live = copy(energy.optJSONObject("live"));
            live.put("power", pointOf(power, true));
            Double full = decimalOf(max);
            if (full == null) required(max);
            live.put("max", full);
            set(live, "warn", decimalOf(warn));
            return with("live", live);
        }

        @Override JSONObject without() { return energy.has("live") ? with("live", null) : null; }
    }

    /** A home battery now: its charge, its power, what it holds. */
    final class BatteryForm extends Form {
        EditText soc, power, capacity;
        Switch reversed;

        @Override String title() { return getString(R.string.energy_battery); }

        @Override void fill() {
            JSONObject b = energy.optJSONObject("battery");
            if (b == null) b = new JSONObject();
            panel.addView(ui.note(getString(R.string.energy_battery_about)));
            soc = point(panel, R.string.energy_battery_soc, 0, b.optString("soc"), new String[] {"percent"}, null);
            power = point(panel, R.string.energy_battery_power, R.string.energy_battery_power_help, b.optString("power"), new String[] {"power"}, null);
            reversed = toggle(panel, R.string.energy_battery_reversed, R.string.energy_battery_reversed_help, b.optBoolean("discharge_positive"));
            capacity = decimal(panel, R.string.energy_battery_capacity, 0, b.opt("capacity_kwh"), "10");
        }

        @Override JSONObject section() throws Invalid, JSONException {
            JSONObject b = copy(energy.optJSONObject("battery"));
            set(b, "soc", pointOf(soc, false));
            set(b, "power", pointOf(power, false));
            set(b, "discharge_positive", reversed.isChecked());
            set(b, "capacity_kwh", decimalOf(capacity));
            return with("battery", b);
        }

        @Override JSONObject without() { return energy.has("battery") ? with("battery", null) : null; }
    }
}
