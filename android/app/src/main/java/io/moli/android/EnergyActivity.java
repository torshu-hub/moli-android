package io.moli.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The house's energy, in the family's words: the electricity contract
 * (one price, or peak and off-peak hours chosen on a clock), what Moli
 * follows (one table of everything that measures, ticked), the water heater
 * Moli can drive, and, folded, the live gauge and a home battery.
 *
 * Nothing is written while choosing: « Enregistrer » writes the whole
 * official `[energy]` section and the block of off-peak hours at once
 * (energy.rs, held to Moli's own check), then restarts Moli once; the page
 * stays as it is. « Voir ce qui sera écrit » shows that part of moli.toml
 * now and after. The single price of a kWh goes where the dashboard's card
 * puts it.
 */
public final class EnergyActivity extends Activity {
    /** What a meter measures (moli-energy `Role`), in the order offered. */
    static final String[] KINDS = {"appliance", "circuit", "total", "grid", "solar", "export", "gas", "charge", "discharge"};
    static final String PEAK = "HP", OFF_PEAK = "HC";

    private final Look ui = new Look(this);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();

    // ---- What the house holds ----

    /** `[energy]` as written in moli.toml (`{}` when the house has none). */
    private JSONObject saved = new JSONObject();
    /** The off-peak hours written (block `heures-creuses`). */
    private JSONArray savedHours = new JSONArray();
    /** The house's points (null: Moli did not answer), and what measures. */
    private JSONArray points;
    private final Map<String, JSONObject> pointById = new HashMap<>();
    private JSONArray measuring = new JSONArray();
    /** The single price of a kWh the dashboard keeps (null: none). */
    private Double housePrice;
    /** The draft as written: the screen has changes when it differs. */
    private String baseline = "", baselineWritten = "";

    // ---- The screen ----

    private boolean busy, ready, heaterPossible;
    private TextView banner, unsaved;
    private Button save;
    private final List<Row> rows = new ArrayList<>();
    private final List<JSONObject> loads = new ArrayList<>();
    private LinearLayout loadsBox;
    private boolean peakHours;
    private EditText singlePrice, peakPrice, offPeakPrice, fee;
    private Stretches contractHours;
    private Switch followLinky;
    private String linkyPeriod;
    private CheckBox liveOn, batteryOn;
    private Spinner livePower, batterySoc, batteryPower;
    private List<JSONObject> liveOptions = new ArrayList<>(), socOptions = new ArrayList<>(), batteryPowerOptions = new ArrayList<>();
    private EditText liveContract, batteryCapacity;
    private Switch batteryReversed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setBackgroundColor(Look.PAGE);
        LinearLayout root = ui.column();
        root.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), 0);
        boolean house = Setup.config(this).exists() && ProtectedKey.exists(this);
        root.addView(ui.note(getString(house ? R.string.energy_reading : R.string.setup_no_house)));
        setContentView(root);
        if (house) load();
    }

    // ---- Reading ----

    private void load() {
        work.execute(() -> {
            try {
                JSONObject read = Setup.energy(this);
                JSONObject house = null;
                try { house = Setup.energyPoints(this); } catch (Exception silent) { /* Moli not answering: what is written only */ }
                JSONObject seen = house;
                handler.post(() -> {
                    if (isDestroyed()) return;
                    take(read);
                    takePoints(seen);
                    makeRows();
                    build();
                    settle();
                    ready = true;
                    refreshChanged();
                });
            } catch (Exception failure) {
                handler.post(() -> {
                    LinearLayout root = ui.column();
                    root.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), 0);
                    root.addView(ui.note(getString(R.string.setup_failed, String.valueOf(failure.getMessage()))));
                    setContentView(root);
                });
            }
        });
    }

    private void take(JSONObject read) {
        saved = read.optJSONObject("section") == null ? new JSONObject() : read.optJSONObject("section");
        savedHours = read.optJSONArray("offpeak") == null ? new JSONArray() : read.optJSONArray("offpeak");
    }

    private void takePoints(JSONObject read) {
        points = read == null ? null : read.optJSONArray("points");
        measuring = read == null || read.optJSONArray("measuring") == null ? new JSONArray() : read.optJSONArray("measuring");
        housePrice = read == null || read.isNull("house_price") ? null : read.optDouble("house_price");
        pointById.clear();
        for (int i = 0; points != null && i < points.length(); i++)
            pointById.put(points.optJSONObject(i).optString("id"), points.optJSONObject(i));
    }

    /** The meters written first, then each device that measures and is not followed yet: rows never move. */
    private void makeRows() {
        rows.clear();
        Set<String> covered = new HashSet<>();
        JSONArray meters = saved.optJSONArray("meter");
        for (int i = 0; meters != null && i < meters.length(); i++) {
            JSONObject m = meters.optJSONObject(i);
            Row r = new Row();
            r.meter = copy(m);
            r.on = true;
            r.name = m.optString("name", m.optString("id"));
            r.point = m.optString("point");
            r.kind = m.optString("role", "appliance");
            r.within = m.optString("within", "");
            r.price = m.has("price") ? m.optDouble("price") : null;
            r.device = deviceOf(r.point);
            rows.add(r);
            covered.add(r.device);
        }
        for (int i = 0; i < measuring.length(); i++) {
            JSONObject d = measuring.optJSONObject(i);
            if (covered.contains(d.optString("device"))) continue;
            Row r = new Row();
            r.name = d.optString("name");
            r.point = d.optString("point");
            r.kind = d.optString("guess", "appliance");
            r.device = d.optString("device");
            rows.add(r);
        }
        loads.clear();
        JSONArray driven = saved.optJSONArray("load");
        for (int i = 0; driven != null && i < driven.length(); i++) loads.add(copy(driven.optJSONObject(i)));
        // A Linky read on the spot says the period itself.
        linkyPeriod = null;
        for (int i = 0; points != null && i < points.length(); i++) {
            JSONObject p = points.optJSONObject(i);
            if ("text".equals(p.optString("family")) && p.optString("device").toLowerCase(Locale.ROOT).contains("linky")) { linkyPeriod = p.optString("id"); break; }
        }
    }

    static String deviceOf(String point) {
        int slash = point.indexOf('/');
        return slash < 0 ? point : point.substring(0, slash);
    }

    static JSONObject copy(JSONObject o) {
        try { return new JSONObject(o == null ? "{}" : o.toString()); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    // ---- Words ----

    String kindWords(String kind) {
        int id = getResources().getIdentifier("energy_kind_" + kind, "string", getPackageName());
        return id == 0 ? kind : getString(id);
    }

    static String number(Object v) {
        if (!(v instanceof Number)) return null;
        return new DecimalFormat("0.##").format(((Number) v).doubleValue());
    }

    /** A number as written in moli.toml, every decimal kept (« 0.2016 »). */
    static String plain(Object v) {
        if (!(v instanceof Number)) return null;
        return java.math.BigDecimal.valueOf(((Number) v).doubleValue()).stripTrailingZeros().toPlainString();
    }

    /** What a point says now: « 52 W », or nothing. */
    String now(String point) {
        JSONObject p = pointById.get(point);
        if (p == null) return "";
        String v = number(p.opt("value"));
        if (v == null) return "";
        return p.isNull("unit") ? v : v + " " + p.optString("unit");
    }

    /** A point as the family reads it: « Télé · Puissance · 52 W ». */
    String pointWords(String id) {
        JSONObject p = pointById.get(id);
        if (p == null) return id;
        String v = now(id);
        return p.optString("name") + " · " + p.optString("label") + (v.isEmpty() ? "" : " · " + v);
    }

    static String clock(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", (minutes / 60) % 24, minutes % 60);
    }

    /** « 22:00-06:00 » as [start, end] minutes, null if unreadable. */
    static int[] stretch(String text) {
        String[] ends = text.split("-");
        if (ends.length != 2) return null;
        int a = minutes(ends[0]), b = minutes(ends[1]);
        return a < 0 || b < 0 ? null : new int[] {a, b};
    }

    static int minutes(String hm) {
        String[] p = hm.trim().split(":");
        try { return p.length == 2 ? Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim()) : -1; } catch (NumberFormatException bad) { return -1; }
    }

    // ---- Layout: two columns on the tablet, one on a phone (the contract first) ----

    private void build() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout root = ui.column();
        root.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0);

        LinearLayout head = ui.row();
        LinearLayout titles = ui.column();
        titles.addView(ui.text(getString(R.string.energy), 24, Look.INK, true));
        unsaved = ui.text(getString(R.string.energy_unsaved), 13, Look.WARN, true);
        unsaved.setVisibility(View.GONE);
        titles.addView(unsaved);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button preview = ui.flat(getString(R.string.energy_preview));
        preview.setOnClickListener(v -> showPreview());
        head.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)));
        save = ui.primary(getString(R.string.energy_save));
        save.setOnClickListener(v -> saveAll(null));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48));
        sp.setMargins(ui.dp(10), 0, 0, 0);
        head.addView(save, sp);
        root.addView(head, ui.matchWrap(0, 0, 0, ui.dp(10)));

        banner = ui.text("", 15, android.graphics.Color.WHITE, false);
        banner.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        banner.setVisibility(View.GONE);
        root.addView(banner, ui.matchWrap(0, 0, 0, ui.dp(10)));
        if (points == null) root.addView(ui.note(getString(R.string.energy_no_moli)));

        LinearLayout first = ui.column(), second = ui.column();
        first.addView(followed(wide));
        first.addView(heater());
        second.addView(contract());
        second.addView(others());

        LinearLayout panes = new LinearLayout(this);
        if (wide) {
            panes.setOrientation(LinearLayout.HORIZONTAL);
            panes.addView(first, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3));
            panes.addView(new View(this), new LinearLayout.LayoutParams(ui.dp(16), 1));
            panes.addView(second, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        } else {
            panes.setOrientation(LinearLayout.VERTICAL);
            panes.addView(second);
            panes.addView(first);
        }
        ScrollView scroll = ui.scroll(panes);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        refreshWithin();
    }

    private LinearLayout section(int title, int about) {
        LinearLayout card = ui.card();
        card.addView(ui.text(getString(title), 18, Look.INK, true));
        if (about != 0) card.addView(ui.note(getString(about)));
        return card;
    }

    /** Any change: compared with what is written, never written by itself. */
    private final TextWatcher typed = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) { refreshChanged(); }
    };

    private final AdapterView.OnItemSelectedListener picked = new AdapterView.OnItemSelectedListener() {
        @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) { refreshChanged(); }
        @Override public void onNothingSelected(AdapterView<?> parent) {}
    };

    private EditText amount(String hint) {
        EditText e = ui.field(hint, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.addTextChangedListener(typed);
        return e;
    }

    /** A labelled value with its unit after it (« 0.2081  €/kWh »). */
    private void labelled(LinearLayout into, String label, View field, String unit) {
        into.addView(ui.text(label, 14, Look.INK, true), ui.matchWrap(0, ui.dp(12), 0, ui.dp(4)));
        LinearLayout line = ui.row();
        line.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (unit != null) {
            TextView u = ui.text(unit, 15, Look.MUTED, false);
            u.setPadding(ui.dp(10), 0, 0, 0);
            line.addView(u);
        }
        into.addView(line);
    }

    private Spinner spinner(String[] words, int selected) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, words));
        s.setSelection(Math.max(0, Math.min(selected, words.length - 1)));
        return s;
    }

    // ---- The contract ----

    private View contract() {
        LinearLayout card = section(R.string.energy_contract, 0);
        JSONObject tariff = saved.optJSONObject("tariff");
        JSONObject prices = tariff == null ? null : tariff.optJSONObject("prices");
        if (prices == null) prices = new JSONObject();
        peakHours = tariff != null;

        RadioGroup formula = new RadioGroup(this);
        formula.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton single = new RadioButton(this);
        single.setText(R.string.energy_single);
        single.setTextSize(16);
        single.setId(View.generateViewId());
        RadioButton peak = new RadioButton(this);
        peak.setText(R.string.energy_peak_hours);
        peak.setTextSize(16);
        peak.setId(View.generateViewId());
        formula.addView(single);
        formula.addView(peak);
        card.addView(formula, ui.matchWrap(0, ui.dp(8), 0, 0));

        LinearLayout singleBox = ui.column();
        singlePrice = amount("0.2516");
        if (housePrice != null) singlePrice.setText(plain(housePrice));
        labelled(singleBox, getString(R.string.energy_single_price), singlePrice, getString(R.string.energy_per_kwh));
        singleBox.addView(ui.note(getString(R.string.energy_single_help)));
        card.addView(singleBox);

        LinearLayout peakBox = ui.column();
        peakPrice = amount("0.2081");
        if (prices.has(PEAK)) peakPrice.setText(plain(prices.opt(PEAK)));
        labelled(peakBox, getString(R.string.energy_peak_price), peakPrice, getString(R.string.energy_per_kwh));
        offPeakPrice = amount("0.1635");
        if (prices.has(OFF_PEAK)) offPeakPrice.setText(plain(prices.opt(OFF_PEAK)));
        labelled(peakBox, getString(R.string.energy_off_peak_price), offPeakPrice, getString(R.string.energy_per_kwh));
        List<String> other = new ArrayList<>();
        for (java.util.Iterator<String> k = prices.keys(); k.hasNext(); ) {
            String name = k.next();
            if (!name.equals(PEAK) && !name.equals(OFF_PEAK)) other.add(name);
        }
        if (!other.isEmpty()) peakBox.addView(ui.note(getString(R.string.energy_other_periods, String.join(", ", other))));

        String period = tariff == null ? "" : tariff.optString("period");
        followLinky = new Switch(this);
        followLinky.setText(R.string.energy_follow_linky);
        followLinky.setTextSize(15);
        followLinky.setChecked(linkyPeriod != null && (period.equals(linkyPeriod) || tariff == null));
        followLinky.setVisibility(linkyPeriod == null ? View.GONE : View.VISIBLE);
        peakBox.addView(followLinky, ui.matchWrap(0, ui.dp(14), 0, 0));
        List<int[]> held = new ArrayList<>();
        for (int i = 0; i < savedHours.length(); i++) {
            int[] s = stretch(savedHours.optString(i));
            if (s != null) held.add(s);
        }
        if (held.isEmpty()) held.add(new int[] {22 * 60, 6 * 60});
        contractHours = new Stretches(R.string.energy_off_peak_hours, R.string.energy_off_peak_help, held);
        peakBox.addView(contractHours.view);
        card.addView(peakBox);

        fee = amount("19.56");
        if (saved.has("monthly_fee")) fee.setText(plain(saved.opt("monthly_fee")));
        labelled(card, getString(R.string.energy_fee), fee, getString(R.string.energy_per_month));
        card.addView(ui.note(getString(R.string.energy_fee_help)));

        Runnable show = () -> {
            singleBox.setVisibility(peakHours ? View.GONE : View.VISIBLE);
            peakBox.setVisibility(peakHours ? View.VISIBLE : View.GONE);
            contractHours.view.setVisibility(followLinky.isChecked() && linkyPeriod != null ? View.GONE : View.VISIBLE);
        };
        formula.check(peakHours ? peak.getId() : single.getId());
        formula.setOnCheckedChangeListener((g, id) -> { peakHours = id == peak.getId(); show.run(); refreshChanged(); });
        followLinky.setOnCheckedChangeListener((b, on) -> { show.run(); refreshChanged(); });
        show.run();
        return card;
    }

    /** Stretches of hours, each « de 22:00 à 06:00 » chosen on a clock, the hours a day under them. */
    final class Stretches {
        final List<int[]> list;
        final LinearLayout view = ui.column();
        private final LinearLayout lines = ui.column();
        private final TextView total = ui.text("", 13, Look.MUTED, false);

        Stretches(int title, int help, List<int[]> held) {
            list = new ArrayList<>(held);
            view.addView(ui.text(getString(title), 14, Look.INK, true), ui.matchWrap(0, ui.dp(12), 0, ui.dp(2)));
            if (help != 0) view.addView(ui.note(getString(help)));
            view.addView(lines);
            Button add = ui.flat(getString(R.string.energy_add_stretch));
            add.setOnClickListener(v -> {
                list.add(list.isEmpty() ? new int[] {22 * 60, 6 * 60} : new int[] {12 * 60, 14 * 60});
                fill();
                refreshChanged();
            });
            view.addView(add, ui.matchWrap(0, ui.dp(6), 0, 0));
            view.addView(total, ui.matchWrap(0, ui.dp(4), 0, 0));
            fill();
        }

        JSONArray written() {
            JSONArray out = new JSONArray();
            for (int[] s : list) out.put(clock(s[0]) + "-" + clock(s[1]));
            return out;
        }

        private void fill() {
            lines.removeAllViews();
            int sum = 0;
            for (int[] s : list) {
                LinearLayout line = ui.row();
                line.addView(ui.text(getString(R.string.energy_from), 15, Look.INK, false));
                line.addView(time(s, 0));
                line.addView(ui.text(getString(R.string.energy_to), 15, Look.INK, false));
                line.addView(time(s, 1));
                Button drop = ui.flat("✕");
                drop.setContentDescription(getString(R.string.energy_remove_stretch));
                drop.setOnClickListener(v -> { list.remove(s); fill(); refreshChanged(); });
                line.addView(drop, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(44)));
                lines.addView(line, ui.matchWrap(0, ui.dp(6), 0, 0));
                sum += (s[1] - s[0] + 24 * 60) % (24 * 60);
            }
            String hours = sum / 60 + " h" + (sum % 60 == 0 ? "" : " " + String.format(Locale.ROOT, "%02d", sum % 60));
            total.setText(list.isEmpty() ? "" : getString(R.string.energy_hours_total, hours));
        }

        private Button time(int[] s, int end) {
            Button b = ui.flat(clock(s[end]));
            b.setTextSize(17);
            b.setOnClickListener(v -> new TimePickerDialog(EnergyActivity.this, (picker, h, m) -> {
                s[end] = h * 60 + m;
                fill();
                refreshChanged();
            }, s[end] / 60, s[end] % 60, true).show());
            LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(ui.dp(96), ui.dp(44));
            margin.setMargins(ui.dp(8), 0, ui.dp(8), 0);
            b.setLayoutParams(margin);
            return b;
        }
    }

    // ---- What Moli follows ----

    /** One line of the table: a device that measures, ticked when Moli counts it. */
    final class Row {
        JSONObject meter;   // as written (null: not followed yet)
        JSONObject pending; // as it would be written now
        boolean on;
        String name, point, kind, within = "", device, newId;
        Double price;
        CheckBox box;
        TextView title, subtitle;
        Spinner kindChoice, withinChoice;
        final List<String> withinIds = new ArrayList<>();
    }

    private View followed(boolean wide) {
        LinearLayout card = section(R.string.energy_followed, R.string.energy_followed_help);
        if (rows.isEmpty()) card.addView(ui.note(getString(points == null ? R.string.energy_no_moli : R.string.energy_nothing_measures)));
        for (Row r : rows) card.addView(row(r, wide));
        TextView whole = ui.note(getString(R.string.energy_no_whole_house));
        whole.setTag("whole");
        card.addView(whole);
        Button page = ui.flat(getString(R.string.energy_open_page));
        page.setOnClickListener(v -> startActivity(new Intent(this, DashboardActivity.class).putExtra(DashboardActivity.ROUTE, "#/energie")));
        card.addView(page, ui.matchWrap(0, ui.dp(8), 0, 0));
        return card;
    }

    private View row(Row r, boolean wide) {
        LinearLayout line = wide ? ui.row() : ui.column();
        line.setPadding(0, ui.dp(10), 0, ui.dp(10));
        LinearLayout first = ui.row();
        r.box = new CheckBox(this);
        r.box.setChecked(r.on);
        first.addView(r.box);
        LinearLayout names = ui.column();
        r.title = ui.text(r.name, 16, Look.INK, true);
        r.subtitle = ui.text(subtitle(r), 13, Look.MUTED, false);
        names.addView(r.title);
        names.addView(r.subtitle);
        first.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView nowText = ui.text(now(r.point), 15, Look.ACCENT, true);
        nowText.setGravity(Gravity.END);
        first.addView(nowText, new LinearLayout.LayoutParams(ui.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));
        line.addView(first, wide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1) : ui.matchWrap(0, 0, 0, 0));

        LinearLayout second = ui.row();
        if (!wide) second.setPadding(ui.dp(40), ui.dp(4), 0, 0);
        String[] words = new String[KINDS.length];
        for (int i = 0; i < KINDS.length; i++) words[i] = kindWords(KINDS[i]);
        r.kindChoice = spinner(words, Arrays.asList(KINDS).indexOf(r.kind));
        r.kindChoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) { refreshWithin(); refreshChanged(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        second.addView(r.kindChoice, new LinearLayout.LayoutParams(ui.dp(wide ? 230 : 200), ui.dp(48)));
        r.withinChoice = new Spinner(this);
        r.withinChoice.setOnItemSelectedListener(picked);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ui.dp(170), ui.dp(48));
        wp.setMargins(ui.dp(6), 0, 0, 0);
        second.addView(r.withinChoice, wp);
        Button more = ui.flat(getString(R.string.energy_details));
        more.setOnClickListener(v -> details(r));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44));
        mp.setMargins(ui.dp(6), 0, 0, 0);
        second.addView(more, mp);
        line.addView(second);
        r.box.setOnCheckedChangeListener((b, on) -> { refreshWithin(); refreshChanged(); });
        return line;
    }

    private String subtitle(Row r) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < measuring.length(); i++) {
            JSONObject d = measuring.optJSONObject(i);
            if (d.optString("device").equals(r.device) && !d.isNull("room")) parts.add(d.optString("room"));
        }
        JSONObject p = pointById.get(r.point);
        if (p != null) parts.add(p.optString("label"));
        else if (points != null) parts.add(getString(R.string.energy_not_answering));
        if (r.price != null) parts.add(getString(R.string.energy_own_price, plain(r.price)));
        return String.join(" · ", parts);
    }

    private String kindOf(Row r) { return KINDS[Math.max(0, r.kindChoice.getSelectedItemPosition())]; }

    /** Ids for the rows not written yet, from their names, in the table's order. */
    private void assignIds() {
        Set<String> taken = new HashSet<>();
        for (Row r : rows) if (r.meter != null) taken.add(r.meter.optString("id"));
        for (Row r : rows) if (r.meter == null) { r.newId = freeId(r.name, taken); taken.add(r.newId); }
    }

    private static String idOf(Row r) { return r.meter != null && r.meter.has("id") ? r.meter.optString("id") : r.newId; }

    static String freeId(String name, Set<String> taken) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "compteur";
        String id = base;
        for (int n = 2; taken.contains(id); n++) id = base + "_" + n;
        return id;
    }

    /** « Dans quel circuit »: the circuits ticked (or the whole house), for an appliance. */
    private void refreshWithin() {
        assignIds();
        List<Row> circuits = new ArrayList<>();
        for (Row r : rows) if (r.box != null && r.box.isChecked() && ("circuit".equals(kindOf(r)) || "total".equals(kindOf(r)))) circuits.add(r);
        boolean whole = false;
        for (Row r : rows) {
            if (r.box == null) continue;
            String k = kindOf(r);
            if (r.box.isChecked() && ("grid".equals(k) || "total".equals(k))) whole = true;
            int at = r.withinChoice.getSelectedItemPosition();
            String keep = at > 0 && at < r.withinIds.size() ? r.withinIds.get(at) : (r.withinIds.isEmpty() ? r.within : "");
            r.withinIds.clear();
            List<String> words = new ArrayList<>();
            r.withinIds.add("");
            words.add(getString(R.string.energy_within_none));
            for (Row c : circuits) {
                if (c == r) continue;
                r.withinIds.add(idOf(c));
                words.add(getString(R.string.energy_within_named, c.name));
            }
            r.withinChoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, words));
            r.withinChoice.setSelection(Math.max(0, r.withinIds.indexOf(keep)));
            r.withinChoice.setVisibility("appliance".equals(k) && r.withinIds.size() > 1 ? View.VISIBLE : View.GONE);
            r.kindChoice.setEnabled(r.box.isChecked());
        }
        View note = getWindow().getDecorView().findViewWithTag("whole");
        if (note != null) note.setVisibility(whole ? View.GONE : View.VISIBLE);
    }

    /** A row's details: the name shown, the measure used, a price of its own. */
    private void details(Row r) {
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        EditText name = ui.field("", InputType.TYPE_CLASS_TEXT);
        name.setText(r.name);
        labelled(box, getString(R.string.energy_shown_name), name, null);
        List<JSONObject> measures = new ArrayList<>();
        for (int i = 0; points != null && i < points.length(); i++) {
            JSONObject p = points.optJSONObject(i);
            String f = p.optString("family"), u = p.optString("unit");
            if (p.optString("device").equals(r.device) && (("power".equals(f) && !"VA".equals(u)) || "energy".equals(f))) measures.add(p);
        }
        Spinner measure = null;
        if (measures.size() > 1) {
            String[] words = new String[measures.size()];
            int at = 0;
            for (int i = 0; i < measures.size(); i++) {
                String id = measures.get(i).optString("id");
                words[i] = measures.get(i).optString("label") + (now(id).isEmpty() ? "" : " · " + now(id));
                if (id.equals(r.point)) at = i;
            }
            measure = spinner(words, at);
            labelled(box, getString(R.string.energy_measure_used), measure, null);
            box.addView(ui.note(getString(R.string.energy_measure_help)));
        }
        EditText price = ui.field(getString(R.string.energy_contract_price), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (r.price != null) price.setText(plain(r.price));
        labelled(box, getString(R.string.energy_own_price_title), price, getString(R.string.energy_per_kwh));
        box.addView(ui.note(getString(R.string.energy_own_price_help)));
        Spinner chosen = measure;
        new AlertDialog.Builder(this)
                .setTitle(r.name)
                .setView(ui.scroll(box))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (!n.isEmpty()) r.name = n;
                    if (chosen != null) r.point = measures.get(chosen.getSelectedItemPosition()).optString("id");
                    Double p = decimal(price);
                    r.price = p != null && !p.isNaN() && p >= 0 ? p : null;
                    r.title.setText(r.name);
                    r.subtitle.setText(subtitle(r));
                    refreshWithin();
                    refreshChanged();
                })
                .show();
    }

    // ---- The water heater ----

    private View heater() {
        LinearLayout card = section(R.string.energy_heater, R.string.energy_heater_help);
        loadsBox = ui.column();
        card.addView(loadsBox);
        return card;
    }

    private List<JSONObject> pointsOf(String family, String unitNot) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; points != null && i < points.length(); i++) {
            JSONObject p = points.optJSONObject(i);
            if (family.equals(p.optString("family")) && (unitNot == null || !unitNot.equals(p.optString("unit")))) out.add(p);
        }
        return out;
    }

    /** The power at the main feed a water heater needs: the whole house, ticked. */
    private String wholeHousePower() {
        for (Row r : rows) if (r.box != null && r.box.isChecked() && "total".equals(kindOf(r))) {
            JSONObject p = pointById.get(r.point);
            if (p != null && "power".equals(p.optString("family"))) return r.point;
        }
        return null;
    }

    private void fillLoads() {
        loadsBox.removeAllViews();
        for (JSONObject l : loads) {
            LinearLayout line = ui.row();
            line.setPadding(0, ui.dp(8), 0, ui.dp(8));
            LinearLayout names = ui.column();
            names.addView(ui.text(l.optString("name"), 16, Look.INK, true));
            JSONArray own = l.optJSONArray("offpeak");
            String power = number(l.opt("power_w"));
            names.addView(ui.text(pointWords(l.optString("switch")) + (power == null ? "" : " · " + power + " W") + " · "
                    + getString(own == null || own.length() == 0 ? R.string.energy_heater_contract_hours : R.string.energy_heater_own_hours), 13, Look.MUTED, false));
            line.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button change = ui.flat(getString(R.string.energy_details));
            change.setOnClickListener(v -> heaterSheet(l));
            line.addView(change, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)));
            loadsBox.addView(line);
        }
        if (heaterPossible) {
            Button add = ui.flat(getString(R.string.energy_heater_add));
            add.setOnClickListener(v -> heaterSheet(null));
            loadsBox.addView(add, ui.matchWrap(0, ui.dp(6), 0, 0));
        } else if (loads.isEmpty()) {
            loadsBox.addView(ui.note(getString(R.string.energy_heater_needs)));
        }
    }

    /** One water heater: which switch, how strong, how long a day, when. */
    private void heaterSheet(JSONObject held) {
        JSONObject l = copy(held);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        EditText name = ui.field(getString(R.string.energy_heater_name), InputType.TYPE_CLASS_TEXT);
        name.setText(l.optString("name", getString(R.string.energy_heater_name)));
        labelled(box, getString(R.string.energy_shown_name), name, null);
        List<JSONObject> switches = pointsOf("switch", null);
        Spinner relay = pointSpinner(switches, l.optString("switch"), false);
        labelled(box, getString(R.string.energy_heater_switch), relay, null);
        List<JSONObject> powers = pointsOf("power", "VA");
        String whole = wholeHousePower();
        Spinner grid = pointSpinner(powers, l.optString("grid", whole == null ? "" : whole), false);
        labelled(box, getString(R.string.energy_heater_grid), grid, null);
        box.addView(ui.note(getString(R.string.energy_heater_grid_help)));
        EditText power = ui.field("2000", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (l.has("power_w")) power.setText(plain(l.opt("power_w")));
        labelled(box, getString(R.string.energy_heater_power), power, "W");
        EditText daily = ui.field("120", InputType.TYPE_CLASS_NUMBER);
        if (l.has("daily_minutes")) daily.setText(plain(l.opt("daily_minutes")));
        labelled(box, getString(R.string.energy_heater_daily), daily, getString(R.string.energy_minutes_a_day));
        JSONArray own = l.optJSONArray("offpeak");
        List<int[]> heldTimes = new ArrayList<>();
        for (int i = 0; own != null && i < own.length(); i++) {
            int[] s = stretch(own.optString(i));
            if (s != null) heldTimes.add(s);
        }
        RadioGroup when = new RadioGroup(this);
        RadioButton contractTimes = new RadioButton(this);
        contractTimes.setText(R.string.energy_heater_contract_hours);
        contractTimes.setId(View.generateViewId());
        RadioButton ownTimes = new RadioButton(this);
        ownTimes.setText(R.string.energy_heater_own_hours);
        ownTimes.setId(View.generateViewId());
        when.addView(contractTimes);
        when.addView(ownTimes);
        box.addView(ui.text(getString(R.string.energy_heater_when), 14, Look.INK, true), ui.matchWrap(0, ui.dp(12), 0, ui.dp(4)));
        box.addView(when);
        Stretches times = new Stretches(R.string.energy_heater_times, 0, heldTimes.isEmpty() ? Arrays.asList(new int[] {22 * 60, 6 * 60}) : heldTimes);
        box.addView(times.view);
        when.setOnCheckedChangeListener((g, id) -> times.view.setVisibility(id == ownTimes.getId() ? View.VISIBLE : View.GONE));
        when.check(heldTimes.isEmpty() ? contractTimes.getId() : ownTimes.getId());
        times.view.setVisibility(heldTimes.isEmpty() ? View.GONE : View.VISIBLE);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(held == null ? getString(R.string.energy_heater_add) : l.optString("name"))
                .setView(ui.scroll(box))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null);
        if (held != null) b.setNeutralButton(R.string.energy_heater_remove, (d, w) -> { loads.remove(held); fillLoads(); refreshChanged(); });
        AlertDialog dialog = b.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Double w = decimal(power);
            if (w == null || w.isNaN() || w <= 0) { power.setError(getString(R.string.energy_required)); return; }
            if (switches.isEmpty() || powers.isEmpty()) { dialog.dismiss(); return; }
            String n = name.getText().toString().trim();
            try {
                l.put("name", n.isEmpty() ? getString(R.string.energy_heater_name) : n);
                if (!l.has("id")) {
                    Set<String> taken = new HashSet<>();
                    for (JSONObject o : loads) taken.add(o.optString("id"));
                    l.put("id", freeId(l.optString("name"), taken));
                }
                l.put("switch", switches.get(relay.getSelectedItemPosition()).optString("id"));
                l.put("grid", powers.get(grid.getSelectedItemPosition()).optString("id"));
                l.put("power_w", w);
                Double m = decimal(daily);
                if (m == null || m.isNaN() || m <= 0) l.remove("daily_minutes"); else l.put("daily_minutes", (int) Math.round(m));
                if (when.getCheckedRadioButtonId() == ownTimes.getId() && !times.list.isEmpty()) l.put("offpeak", times.written());
                else l.remove("offpeak");
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            if (held == null) loads.add(l); else loads.set(loads.indexOf(held), l);
            fillLoads();
            refreshChanged();
            dialog.dismiss();
        }));
        dialog.show();
    }

    /** A choice among points, by their words; the one written chosen (or the first). */
    private Spinner pointSpinner(List<JSONObject> options, String selected, boolean none) {
        List<String> words = new ArrayList<>();
        if (none) words.add(getString(R.string.energy_none));
        int at = 0;
        for (int i = 0; i < options.size(); i++) {
            words.add(pointWords(options.get(i).optString("id")));
            if (options.get(i).optString("id").equals(selected)) at = i + (none ? 1 : 0);
        }
        if (words.isEmpty()) words.add(getString(R.string.energy_none));
        Spinner s = spinner(words.toArray(new String[0]), at);
        s.setOnItemSelectedListener(picked);
        return s;
    }

    private static String chosen(Spinner s, List<JSONObject> options, boolean none) {
        int at = s.getSelectedItemPosition() - (none ? 1 : 0);
        return at < 0 || at >= options.size() ? "" : options.get(at).optString("id");
    }

    // ---- Other settings: the live gauge, a home battery (folded) ----

    private View others() {
        LinearLayout card = section(R.string.energy_others, 0);
        LinearLayout part = ui.column();
        JSONObject live = saved.optJSONObject("live");
        JSONObject battery = saved.optJSONObject("battery");
        part.setVisibility(live != null || battery != null ? View.VISIBLE : View.GONE);
        Button fold = ui.flat(getString(R.string.energy_others_open));
        fold.setOnClickListener(v -> part.setVisibility(part.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        card.addView(fold, ui.matchWrap(0, ui.dp(6), 0, 0));
        card.addView(part);

        liveOptions = pointsOf("power", null);
        liveOn = new CheckBox(this);
        liveOn.setText(R.string.energy_live_on);
        liveOn.setTextSize(15);
        liveOn.setChecked(live != null);
        part.addView(liveOn, ui.matchWrap(0, ui.dp(12), 0, 0));
        LinearLayout liveBox = ui.column();
        String guess = live == null ? "" : live.optString("power");
        if (guess.isEmpty()) for (JSONObject p : liveOptions) if ("VA".equals(p.optString("unit"))) { guess = p.optString("id"); break; }
        livePower = pointSpinner(liveOptions, guess, false);
        labelled(liveBox, getString(R.string.energy_live_power), livePower, null);
        liveContract = amount("9");
        if (live != null && live.has("max")) liveContract.setText(plain(live.optDouble("max") / 1000));
        labelled(liveBox, getString(R.string.energy_live_contract), liveContract, "kVA");
        liveBox.addView(ui.note(getString(R.string.energy_live_help)));
        part.addView(liveBox);
        if (liveOptions.isEmpty() && live == null) { liveOn.setEnabled(false); part.addView(ui.note(getString(R.string.energy_live_none))); }
        liveOn.setOnCheckedChangeListener((b, on) -> { liveBox.setVisibility(on ? View.VISIBLE : View.GONE); refreshChanged(); });
        liveBox.setVisibility(liveOn.isChecked() ? View.VISIBLE : View.GONE);

        socOptions = pointsOf("percent", null);
        batteryPowerOptions = pointsOf("power", "VA");
        batteryOn = new CheckBox(this);
        batteryOn.setText(R.string.energy_battery_on);
        batteryOn.setTextSize(15);
        batteryOn.setChecked(battery != null);
        part.addView(batteryOn, ui.matchWrap(0, ui.dp(16), 0, 0));
        LinearLayout batteryBox = ui.column();
        batterySoc = pointSpinner(socOptions, battery == null ? "" : battery.optString("soc"), true);
        labelled(batteryBox, getString(R.string.energy_battery_soc), batterySoc, null);
        batteryPower = pointSpinner(batteryPowerOptions, battery == null ? "" : battery.optString("power"), true);
        labelled(batteryBox, getString(R.string.energy_battery_power), batteryPower, null);
        batteryReversed = new Switch(this);
        batteryReversed.setText(R.string.energy_battery_reversed);
        batteryReversed.setChecked(battery != null && battery.optBoolean("discharge_positive"));
        batteryReversed.setOnCheckedChangeListener((b, on) -> refreshChanged());
        batteryBox.addView(batteryReversed, ui.matchWrap(0, ui.dp(10), 0, 0));
        batteryCapacity = amount("10");
        if (battery != null && battery.has("capacity_kwh")) batteryCapacity.setText(plain(battery.opt("capacity_kwh")));
        labelled(batteryBox, getString(R.string.energy_battery_capacity), batteryCapacity, "kWh");
        part.addView(batteryBox);
        if (socOptions.isEmpty() && battery == null) { batteryOn.setEnabled(false); part.addView(ui.note(getString(R.string.energy_battery_none))); }
        batteryOn.setOnCheckedChangeListener((b, on) -> { batteryBox.setVisibility(on ? View.VISIBLE : View.GONE); refreshChanged(); });
        batteryBox.setVisibility(batteryOn.isChecked() ? View.VISIBLE : View.GONE);
        return card;
    }

    // ---- The draft: what would be written ----

    /** A value the screen cannot write: its field says why. */
    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;
        Invalid() { super(null, null, false, false); }
    }

    /** A typed number (a comma accepted); null when empty, NaN when unreadable. */
    static Double decimal(EditText e) {
        String t = e.getText().toString().trim().replace(',', '.');
        if (t.isEmpty()) return null;
        try { return Double.parseDouble(t); } catch (NumberFormatException bad) { return Double.NaN; }
    }

    private Double amountOf(EditText e, boolean needed, boolean show) throws Invalid {
        Double v = decimal(e);
        if ((v == null && needed) || (v != null && (v.isNaN() || v < 0))) {
            if (show) { e.setError(getString(v == null ? R.string.energy_required : R.string.energy_number_invalid)); e.requestFocus(); }
            throw new Invalid();
        }
        return v;
    }

    static void set(JSONObject o, String key, Object value) throws JSONException {
        if (value == null || "".equals(value) || Boolean.FALSE.equals(value)) o.remove(key); else o.put(key, value);
    }

    /** The `[energy]` section the screen says, from what is written (keys it does not show kept). */
    private JSONObject draft(boolean show) throws Invalid, JSONException {
        JSONObject next = copy(saved);
        assignIds();
        JSONArray meters = new JSONArray();
        for (Row r : rows) {
            r.pending = null;
            if (!r.box.isChecked()) continue;
            JSONObject m = copy(r.meter);
            if (!m.has("id")) m.put("id", r.newId);
            String kind = kindOf(r);
            JSONObject p = pointById.get(r.point);
            boolean power = p != null ? "power".equals(p.optString("family")) : m.optBoolean("integrate");
            m.put("name", r.name);
            m.put("role", kind);
            m.put("point", r.point);
            set(m, "integrate", power);
            if (power || !"appliance".equals(kind)) m.remove("estimate");
            int w = r.withinChoice.getSelectedItemPosition();
            set(m, "within", "appliance".equals(kind) && w > 0 && w < r.withinIds.size() ? r.withinIds.get(w) : null);
            boolean unpriced = "solar".equals(kind) || "charge".equals(kind) || "discharge".equals(kind);
            set(m, "price", unpriced ? null : r.price);
            if (unpriced) { m.remove("price_point"); m.remove("price_extra"); }
            r.pending = m;
            meters.put(m);
        }
        set(next, "meter", meters.length() == 0 ? null : meters);
        JSONArray driven = new JSONArray();
        for (JSONObject l : loads) driven.put(l);
        set(next, "load", driven.length() == 0 ? null : driven);

        set(next, "monthly_fee", amountOf(fee, false, show));
        if (peakHours) {
            JSONObject tariff = copy(saved.optJSONObject("tariff"));
            JSONObject prices = copy(tariff.optJSONObject("prices"));
            prices.put(PEAK, amountOf(peakPrice, true, show));
            prices.put(OFF_PEAK, amountOf(offPeakPrice, true, show));
            tariff.put("prices", prices);
            if (followLinky.isChecked() && linkyPeriod != null) tariff.put("period", linkyPeriod);
            else {
                // Moli follows the hours below (energy.rs fills the period in).
                tariff.remove("period");
                if (contractHours.list.isEmpty()) {
                    if (show) banner(getString(R.string.energy_hours_needed), Look.BAD);
                    throw new Invalid();
                }
            }
            next.put("tariff", tariff);
        } else {
            next.remove("tariff");
            amountOf(singlePrice, false, show);
        }

        // Without Moli's points, what is written stays as it is.
        if (liveOn.isChecked() && liveOptions.isEmpty()) { /* kept */ }
        else if (liveOn.isChecked()) {
            JSONObject live = copy(saved.optJSONObject("live"));
            live.put("power", chosen(livePower, liveOptions, false));
            double max = amountOf(liveContract, true, show) * 1000;
            live.put("max", max);
            if (live.optDouble("warn", 0) > max) live.remove("warn");
            next.put("live", live);
        } else next.remove("live");

        if (batteryOn.isChecked() && socOptions.isEmpty() && batteryPowerOptions.isEmpty()) { /* kept */ }
        else if (batteryOn.isChecked()) {
            JSONObject b = copy(saved.optJSONObject("battery"));
            set(b, "soc", chosen(batterySoc, socOptions, true));
            set(b, "power", chosen(batteryPower, batteryPowerOptions, true));
            set(b, "discharge_positive", batteryReversed.isChecked());
            set(b, "capacity_kwh", amountOf(batteryCapacity, false, show));
            if (!b.has("soc") && !b.has("power")) {
                if (show) banner(getString(R.string.energy_battery_needed), Look.BAD);
                throw new Invalid();
            }
            next.put("battery", b);
        } else next.remove("battery");

        // Moli keeps a single price under [energy]: the section must exist.
        if (next.length() == 0 && !peakHours && decimal(singlePrice) != null) next.put("currency", "€");
        return next;
    }

    /** The off-peak hours to write: the contract's, or none (taken out). */
    private JSONArray draftHours() {
        if (!peakHours || (followLinky.isChecked() && linkyPeriod != null)) return new JSONArray();
        return contractHours.written();
    }

    /** The single price to give Moli, if it changed (null: nothing to do). */
    private Double draftPrice() {
        if (peakHours) return null;
        Double p = decimal(singlePrice);
        if (p == null || p.isNaN() || p <= 0) return null;
        return housePrice != null && Math.abs(housePrice - p) < 1e-9 ? null : p;
    }

    /** What would be written in moli.toml, and in all (with the single price); null if incomplete. */
    private String written() {
        try { return draft(false).toString() + draftHours(); } catch (Invalid | JSONException incomplete) { return null; }
    }

    private String signature() {
        String w = written();
        return w == null ? null : w + draftPrice();
    }

    /** The draft as written is the new reference. */
    private void settle() {
        baselineWritten = written() == null ? "" : written();
        baseline = signature() == null ? "" : signature();
    }

    private void refreshChanged() {
        if (!ready || save == null) return;
        String now = signature();
        boolean changed = now == null || !now.equals(baseline);
        save.setEnabled(changed && !busy);
        save.setAlpha(changed ? 1f : 0.45f);
        unsaved.setVisibility(changed ? View.VISIBLE : View.GONE);
        boolean possible = !pointsOf("switch", null).isEmpty() && wholeHousePower() != null;
        if (possible != heaterPossible || loadsBox.getChildCount() == 0) { heaterPossible = possible; fillLoads(); }
    }

    // ---- Seeing, saving ----

    private void banner(String text, int color) {
        banner.setText(text);
        banner.setBackground(ui.round(color, 10));
        banner.setVisibility(View.VISIBLE);
    }

    /** « Voir ce qui sera écrit »: the part of moli.toml now, and after « Enregistrer ». */
    private void showPreview() {
        JSONObject section;
        try { section = draft(true); } catch (Invalid shown) { return; } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        JSONArray hours = draftHours();
        Double price = draftPrice();
        work.execute(() -> {
            JSONObject seen;
            try { seen = Setup.energyPreview(this, section, hours); } catch (Exception failure) { seen = null; }
            JSONObject shown = seen;
            handler.post(() -> {
                if (isDestroyed()) return;
                LinearLayout box = ui.column();
                box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
                if (shown == null) box.addView(ui.note(getString(R.string.energy_preview_failed)));
                else {
                    box.addView(ui.text(getString(R.string.energy_preview_before), 14, Look.INK, true));
                    box.addView(code(shown.optString("before")));
                    box.addView(ui.text(getString(R.string.energy_preview_after), 14, Look.INK, true), ui.matchWrap(0, ui.dp(14), 0, 0));
                    if (!shown.isNull("error")) box.addView(ui.text(getString(R.string.energy_preview_refused, shown.optString("error")), 14, Look.BAD, false));
                    else box.addView(code(shown.optString("after")));
                    if (price != null) box.addView(ui.note(getString(R.string.energy_preview_price, plain(price))));
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.energy_preview)
                        .setView(ui.scroll(box))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    private TextView code(String text) {
        TextView t = ui.text(text.trim().isEmpty() ? getString(R.string.energy_preview_nothing) : text.trim(), 12, Look.INK, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        t.setBackground(ui.round(Look.PAGE, 8));
        return t;
    }

    /** One check by Moli, one restart; the page stays as it is. `after`: once saved. */
    private void saveAll(Runnable after) {
        if (busy) return;
        JSONObject section;
        try { section = draft(true); } catch (Invalid shown) { return; } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        JSONArray hours = draftHours();
        Double price = draftPrice();
        boolean write = !(section.toString() + hours).equals(baselineWritten);
        busy = true;
        save.setEnabled(false);
        banner(getString(write ? R.string.setup_restarting : R.string.energy_saving), Look.INFO);
        work.execute(() -> {
            String refused = null;
            try {
                if (write) refused = Setup.energyPut(this, section, hours);
                if (refused == null && price != null) refused = Setup.energyPrice(this, price);
            } catch (Exception failure) { refused = String.valueOf(failure.getMessage()); }
            JSONObject read = null;
            if (refused == null) try { read = Setup.energy(this); } catch (Exception silent) { /* the screen's draft stands */ }
            String why = refused;
            JSONObject now = read;
            handler.post(() -> {
                busy = false;
                if (isDestroyed()) return;
                if (why == null) {
                    if (now != null) take(now);
                    else { saved = section; savedHours = hours; }
                    if (price != null) housePrice = price;
                    for (Row r : rows) r.meter = r.box.isChecked() ? r.pending : null;
                    settle();
                    refreshChanged();
                    banner(getString(R.string.energy_saved), Look.OK);
                    handler.postDelayed(() -> { if (!busy) banner.setVisibility(View.GONE); }, 5000);
                    if (after != null) after.run();
                } else {
                    banner.setVisibility(View.GONE);
                    refreshChanged();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.setup_refused)
                            .setMessage(getString(R.string.energy_refused_kept, why))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            });
        });
    }

    @Override public void onBackPressed() {
        if (busy) return;
        String now = ready ? signature() : baseline;
        if (now != null && now.equals(baseline)) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this)
                .setTitle(R.string.energy_unsaved)
                .setMessage(R.string.energy_unsaved_question)
                .setPositiveButton(R.string.energy_save, (d, w) -> saveAll(this::finish))
                .setNegativeButton(R.string.energy_discard, (d, w) -> finish())
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    @Override protected void onDestroy() { work.shutdown(); super.onDestroy(); }
}
