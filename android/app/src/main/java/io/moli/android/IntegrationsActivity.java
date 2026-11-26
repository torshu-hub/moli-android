package io.moli.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The integrations of the house, set up from the tablet instead of a
 * computer: the official catalogue, one form per package (its
 * integration.toml), its official guide beside it (onboarding.md). Saving
 * writes the official `[[driver]]` block and vault (Setup), then restarts
 * Moli. The live state comes from Moli's public /api/health.
 */
public final class IntegrationsActivity extends Activity {
    private static final int INK = 0xFF1B2733, MUTED = 0xFF5C6B7A, PAGE = 0xFFF2F4F7,
            CARD = Color.WHITE, ACCENT = 0xFF234E70, LINE = 0xFFDDE3EA,
            OK = 0xFF2E7D4F, WARN = 0xFFB26A00, BAD = 0xFFB3261E, INFO = 0xFF2F5FA7;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService probe = Executors.newSingleThreadExecutor();

    private JSONArray catalogue = new JSONArray(), drivers = new JSONArray();
    /** instance → its `status` in /api/health. */
    private final Map<String, JSONObject> live = new HashMap<>();
    private boolean wide, busy;
    private LinearLayout house, side;
    private TextView banner;
    private String query = "";
    /** What the right pane shows: null (catalogue), else the open form. */
    private Form form;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            int port = MoliService.port;
            probe.execute(() -> {
                Map<String, JSONObject> now = health(port);
                handler.post(() -> {
                    if (isDestroyed()) return;
                    if (now != null) { live.clear(); live.putAll(now); }
                    if (!busy) showHouse();
                });
            });
            handler.postDelayed(this, 3000);
        }
    };

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().getDecorView().setBackgroundColor(PAGE);
        build();
        if (!Setup.config(this).exists() || !ProtectedKey.exists(this)) {
            banner.setText(R.string.setup_no_house);
            banner.setVisibility(View.VISIBLE);
            return;
        }
        load(null);
    }

    /** Turned: rebuilt only if the layout changes (what is typed stays otherwise). */
    @Override public void onConfigurationChanged(Configuration changed) {
        super.onConfigurationChanged(changed);
        if ((changed.screenWidthDp >= 720) == wide) return;
        build();
        showHouse();
        showSide();
    }

    /** Two panes side by side on a tablet, one under the other on a phone. */
    private void build() {
        wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), 0);
        banner = text("", 15, Color.WHITE, false);
        banner.setPadding(dp(14), dp(10), dp(14), dp(10));
        banner.setBackground(round(INFO, 10));
        banner.setVisibility(View.GONE);
        root.addView(banner, matchWrap(0, 0, 0, dp(10)));
        LinearLayout panes = new LinearLayout(this);
        panes.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        house = column();
        side = column();
        if (wide) {
            panes.addView(scroll(house), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2));
            View gap = new View(this);
            panes.addView(gap, new LinearLayout.LayoutParams(dp(20), 1));
            panes.addView(scroll(side), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3));
            root.addView(panes, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            LinearLayout both = column();
            both.addView(house);
            both.addView(side, matchWrap(0, dp(16), 0, 0));
            root.addView(scroll(both), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        setContentView(root);
    }

    // ---- Data ----

    /** Reads the catalogue and the house's blocks; then opens `open` (an instance) if given. */
    private void load(String open) {
        work.execute(() -> {
            try {
                JSONArray all = Setup.catalogue(this), mine = Setup.drivers(this);
                handler.post(() -> {
                    catalogue = all;
                    drivers = mine;
                    form = null;
                    if (open != null) for (int i = 0; i < mine.length(); i++)
                        if (open.equals(mine.optJSONObject(i).optString("id"))) form = Form.edit(this, mine.optJSONObject(i));
                    showHouse();
                    showSide();
                    handler.removeCallbacks(poll);
                    handler.post(poll);
                });
            } catch (Exception failure) {
                handler.post(() -> fail(failure.getMessage()));
            }
        });
    }

    /** Moli's drivers and their state, from the public health route (null: Moli is not answering). */
    private static Map<String, JSONObject> health(int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/health").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            try {
                if (c.getResponseCode() != 200) return null;
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
                }
                String body = bytes.toString("UTF-8");
                JSONArray list = new JSONObject(body).optJSONArray("drivers");
                Map<String, JSONObject> out = new HashMap<>();
                if (list != null) for (int i = 0; i < list.length(); i++) {
                    JSONObject d = list.optJSONObject(i);
                    out.put(d.optString("instance"), d.optJSONObject("status"));
                }
                return out;
            } finally { c.disconnect(); }
        } catch (Exception unreachable) {
            return null;
        }
    }

    JSONObject packageOf(String id) {
        for (int i = 0; i < catalogue.length(); i++)
            if (catalogue.optJSONObject(i).optString("id").equals(id)) return catalogue.optJSONObject(i);
        return null;
    }

    boolean taken(String id) {
        for (int i = 0; i < drivers.length(); i++)
            if (drivers.optJSONObject(i).optString("id").equals(id)) return true;
        return false;
    }

    // ---- Left pane: the house ----

    private void showHouse() {
        if (house == null) return;
        house.removeAllViews();
        house.addView(heading(getString(R.string.setup_house)));
        if (drivers.length() == 0) house.addView(note(getString(R.string.setup_house_empty)));
        for (int i = 0; i < drivers.length(); i++) house.addView(driverCard(drivers.optJSONObject(i)));
        Button board = flat(getString(R.string.setup_see_dashboard));
        board.setOnClickListener(v -> openDashboard("#/systeme"));
        house.addView(board, matchWrap(0, dp(4), 0, 0));
        house.addView(note(getString(R.string.setup_added_elsewhere)));
    }

    private View driverCard(JSONObject d) {
        LinearLayout card = card();
        String id = d.optString("id");
        JSONObject pkg = packageOf(d.optString("integration"));
        String name = pkg != null ? pkg.optString("name") : d.optString("kind");
        LinearLayout top = row();
        TextView title = text(name, 17, INK, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(state(d));
        card.addView(top);
        TextView sub = text(id, 13, MUTED, false);
        sub.setTypeface(Typeface.MONOSPACE);
        card.addView(sub);
        JSONObject status = live.get(id);
        String why = status == null ? null : status.has("error") ? status.optString("error") : status.optString("reason", null);
        if (why != null && !why.isEmpty()) card.addView(text(why, 13, MUTED, false), matchWrap(0, dp(4), 0, 0));
        boolean open = form != null && id.equals(form.previous);
        card.setBackground(open ? stroke(CARD, ACCENT, 14) : round(CARD, 14));
        card.setOnClickListener(v -> { if (!busy) { form = Form.edit(this, d); showHouse(); showSide(); } });
        return card;
    }

    /** A coloured chip: the driver's state as Moli reports it. */
    private TextView state(JSONObject d) {
        String word; int color;
        JSONObject status = live.get(d.optString("id"));
        if (!d.optBoolean("enabled", true)) { word = getString(R.string.setup_state_off); color = MUTED; }
        else if (status == null) { word = getString(R.string.setup_state_unknown); color = MUTED; }
        else switch (status.optString("state")) {
            case "running": word = getString(R.string.setup_state_running); color = OK; break;
            case "starting": word = getString(R.string.setup_state_starting); color = INFO; break;
            case "waiting": word = getString(R.string.setup_state_waiting); color = INFO; break;
            case "backoff": word = getString(R.string.setup_state_backoff); color = WARN; break;
            default: word = getString(R.string.setup_state_stopped); color = BAD;
        }
        TextView chip = text(word, 12, Color.WHITE, true);
        chip.setPadding(dp(10), dp(3), dp(10), dp(3));
        chip.setBackground(round(color, 20));
        return chip;
    }

    // ---- Right pane: the catalogue, or a form ----

    private void showSide() {
        if (side == null) return;
        side.removeAllViews();
        if (form != null) { form.show(side); return; }
        side.addView(heading(getString(R.string.setup_catalogue)));
        EditText search = field(getString(R.string.setup_search), InputType.TYPE_CLASS_TEXT);
        search.setText(query);
        side.addView(search, matchWrap(0, 0, 0, dp(10)));
        LinearLayout list = column();
        side.addView(list);
        Runnable fill = () -> {
            list.removeAllViews();
            List<JSONObject> shown = new ArrayList<>();
            String q = query.toLowerCase(Locale.ROOT).trim();
            for (int i = 0; i < catalogue.length(); i++) {
                JSONObject p = catalogue.optJSONObject(i);
                String hay = (p.optString("id") + " " + p.optString("name") + " " + p.optJSONArray("brands") + " " + p.optJSONArray("models")).toLowerCase(Locale.ROOT);
                if (q.isEmpty() || hay.contains(q)) shown.add(p);
            }
            shown.sort((a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
            int per = wide ? 2 : 1;
            LinearLayout line = null;
            for (int i = 0; i < shown.size(); i++) {
                if (i % per == 0) { line = row(); list.addView(line); }
                View card = packageCard(shown.get(i));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(i % per == 0 ? 0 : dp(6), 0, i % per == per - 1 ? 0 : dp(6), 0);
                line.addView(card, lp);
            }
            if (line != null && shown.size() % per != 0) line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            if (shown.isEmpty()) list.addView(note(getString(R.string.setup_nothing_found)));
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { query = s.toString(); fill.run(); }
        });
        fill.run();
    }

    private View packageCard(JSONObject p) {
        LinearLayout card = card();
        card.addView(text(p.optString("name"), 16, INK, true));
        JSONArray brands = p.optJSONArray("brands");
        StringBuilder who = new StringBuilder();
        for (int i = 0; brands != null && i < brands.length(); i++) who.append(i == 0 ? "" : ", ").append(brands.optString(i));
        if (who.length() > 0) card.addView(text(who.toString(), 13, MUTED, false));
        String hint = p.optBoolean("addable") ? getString(R.string.setup_by_address)
                : p.optJSONArray("secrets").length() > 0 ? getString(R.string.setup_needs_secret)
                : getString(R.string.setup_no_secret);
        card.addView(text(hint, 12, ACCENT, false), matchWrap(0, dp(6), 0, 0));
        card.setOnClickListener(v -> { if (!busy) { form = Form.add(this, p); showHouse(); showSide(); } });
        return card;
    }

    void closeForm() { form = null; showHouse(); showSide(); }

    // ---- Changes ----

    /** Runs a change off the UI thread; Moli restarts meanwhile. */
    void apply(String instance, Change change) {
        busy = true;
        banner.setText(R.string.setup_restarting);
        banner.setBackground(round(INFO, 10));
        banner.setVisibility(View.VISIBLE);
        if (form != null) form.lock(true);
        work.execute(() -> {
            String refused;
            try { refused = change.run(); } catch (Exception failure) { refused = String.valueOf(failure.getMessage()); }
            String why = refused;
            handler.post(() -> {
                busy = false;
                if (isDestroyed()) return;
                if (why == null) {
                    banner.setText(R.string.setup_done);
                    banner.setBackground(round(OK, 10));
                    handler.postDelayed(() -> banner.setVisibility(View.GONE), 4000);
                    load(instance);
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

    interface Change { String run() throws Exception; }

    private void fail(String message) {
        banner.setText(getString(R.string.setup_failed, String.valueOf(message)));
        banner.setBackground(round(BAD, 10));
        banner.setVisibility(View.VISIBLE);
    }

    void openDashboard(String route) {
        startActivity(new Intent(this, DashboardActivity.class).putExtra(DashboardActivity.ROUTE, route));
    }

    @Override public void onBackPressed() {
        if (form != null && !busy) closeForm(); else super.onBackPressed();
    }

    @Override protected void onStart() { super.onStart(); if (catalogue.length() > 0) handler.post(poll); }
    @Override protected void onStop() { handler.removeCallbacks(poll); super.onStop(); }
    @Override protected void onDestroy() { work.shutdown(); probe.shutdownNow(); super.onDestroy(); }

    // ---- Small views ----

    GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    GradientDrawable stroke(int color, int line, int radius) {
        GradientDrawable g = round(color, radius);
        g.setStroke(dp(2), line);
        return g;
    }

    TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    TextView heading(String value) {
        TextView t = text(value, 20, INK, true);
        t.setPadding(0, dp(8), 0, dp(10));
        return t;
    }

    TextView note(String value) {
        TextView t = text(value, 13, MUTED, false);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout card() {
        LinearLayout c = column();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(round(CARD, 14));
        c.setElevation(dp(1));
        c.setLayoutParams(matchWrap(0, 0, 0, dp(10)));
        return c;
    }

    ScrollView scroll(View content) {
        ScrollView s = new ScrollView(this);
        s.setClipToPadding(false);
        s.setPadding(0, 0, 0, dp(24));
        s.addView(content);
        return s;
    }

    EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextSize(16);
        e.setTextColor(INK);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(stroke(CARD, LINE, 10));
        return e;
    }

    Button primary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(round(ACCENT, 10));
        return b;
    }

    Button flat(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(ACCENT);
        b.setBackground(stroke(PAGE, ACCENT, 10));
        return b;
    }

    LinearLayout.LayoutParams matchWrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    /** A friendly name for a field (`field_<name>`), else the name itself. */
    String label(String name) {
        int id = getResources().getIdentifier("field_" + name.replaceAll("[^a-z0-9_]", "_"), "string", getPackageName());
        return id == 0 ? name : getString(id);
    }

    /** An example for a field, when the name says what it holds. */
    String example(String name) {
        int id = getResources().getIdentifier("example_" + name.replaceAll("[^a-z0-9_]", "_"), "string", getPackageName());
        return id == 0 ? "" : getString(id);
    }

    /** The official guide (Markdown): titles, lists, `code`, **bold**. */
    CharSequence markdown(String source) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (String line : source.split("\n")) {
            int start = out.length();
            boolean title = line.startsWith("#");
            if (title) line = line.replaceFirst("^#+\\s*", "");
            inline(out, line);
            if (title) {
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(1.2f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            out.append('\n');
        }
        return out;
    }

    private void inline(SpannableStringBuilder out, String line) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            int close;
            if (c == '`' && (close = line.indexOf('`', i + 1)) > i) {
                int start = out.length();
                out.append(line, i + 1, close);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(PAGE), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = close + 1;
            } else if (line.startsWith("**", i) && (close = line.indexOf("**", i + 2)) > i) {
                int start = out.length();
                out.append(line, i + 2, close);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = close + 2;
            } else {
                out.append(c);
                i++;
            }
        }
    }

    /** One package's form: a new instance, or a block of the house. */
    static final class Form {
        final IntegrationsActivity screen;
        final JSONObject pkg;
        /** The block changed (its id), null for a new one. */
        final String previous;
        final JSONObject block;
        final Map<String, EditText> fields = new LinkedHashMap<>(), vars = new LinkedHashMap<>(), secrets = new LinkedHashMap<>();
        EditText id, extra, otherName, otherValue;
        Switch enabled;
        final List<View> controls = new ArrayList<>();

        private Form(IntegrationsActivity screen, JSONObject pkg, JSONObject block) {
            this.screen = screen;
            this.pkg = pkg;
            this.block = block;
            this.previous = block == null ? null : block.optString("id");
        }

        static Form add(IntegrationsActivity screen, JSONObject pkg) { return new Form(screen, pkg, null); }

        static Form edit(IntegrationsActivity screen, JSONObject block) {
            return new Form(screen, screen.packageOf(block.optString("integration")), block);
        }

        void show(LinearLayout side) {
            IntegrationsActivity s = screen;
            if (pkg == null) {
                side.addView(s.heading(previous));
                side.addView(s.note(s.getString(R.string.setup_unknown_package, block.optString("kind"))));
                Button back = s.flat(s.getString(android.R.string.cancel));
                back.setOnClickListener(v -> s.closeForm());
                side.addView(back);
                return;
            }
            LinearLayout top = s.row();
            TextView title = s.heading(pkg.optString("name"));
            top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button close = s.flat("✕");
            close.setOnClickListener(v -> s.closeForm());
            top.addView(close, new LinearLayout.LayoutParams(s.dp(48), s.dp(44)));
            side.addView(top);

            LinearLayout panel = s.card();
            side.addView(panel);
            if (pkg.optBoolean("addable") && previous == null) {
                panel.addView(s.text(s.getString(R.string.setup_by_address_explained), 15, INK, false));
                Button go = s.primary(s.getString(R.string.setup_open_add));
                go.setOnClickListener(v -> s.openDashboard("#/ajouter"));
                panel.addView(go, s.matchWrap(0, s.dp(12), 0, 0));
                if (brand("Meross")) side.addView(merossAccount());
                guide(side);
                return;
            }

            id = s.field("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            id.setText(previous != null ? previous : freeId());
            id.setEnabled(previous == null);
            labelled(panel, s.getString(R.string.setup_instance), previous == null ? s.getString(R.string.setup_instance_help) : s.getString(R.string.setup_instance_fixed), id);

            JSONObject shownVars = block == null ? new JSONObject() : block.optJSONObject("vars");
            JSONObject shownFields = block == null ? new JSONObject() : block.optJSONObject("fields");
            for (String name : names("vars")) vars.put(name, input(panel, name, shownVars.optString(name, ""), false));
            for (String name : names("options")) fields.put(name, input(panel, name, shownFields.optString(name, ""), false));
            for (String name : names("secrets")) secrets.put(name, input(panel, name, "", true));

            enabled = new Switch(s);
            enabled.setText(R.string.setup_enabled);
            enabled.setTextSize(15);
            enabled.setChecked(block == null || block.optBoolean("enabled", true));
            panel.addView(enabled, s.matchWrap(0, s.dp(14), 0, 0));
            controls.add(enabled);

            // What the fields do not cover: other options, another secret.
            LinearLayout more = s.column();
            more.setVisibility(block != null && !block.optString("extra").isEmpty() ? View.VISIBLE : View.GONE);
            Button toggle = s.flat(s.getString(R.string.setup_advanced));
            toggle.setOnClickListener(v -> more.setVisibility(more.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            panel.addView(toggle, s.matchWrap(0, s.dp(14), 0, 0));
            panel.addView(more);
            extra = s.field(s.getString(R.string.example_extra), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            extra.setTypeface(Typeface.MONOSPACE);
            extra.setMinLines(3);
            extra.setGravity(Gravity.TOP);
            if (block != null) extra.setText(block.optString("extra"));
            labelled(more, s.getString(R.string.setup_extra), s.getString(R.string.setup_extra_help), extra);
            otherName = s.field(s.getString(R.string.setup_other_secret_name), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            otherValue = s.field(s.getString(R.string.setup_other_secret_value), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            labelled(more, s.getString(R.string.setup_other_secret), s.getString(R.string.setup_other_secret_help), otherName);
            more.addView(otherValue, s.matchWrap(0, s.dp(6), 0, 0));
            controls.add(toggle);

            LinearLayout actions = s.row();
            Button save = s.primary(s.getString(previous == null ? R.string.setup_add : R.string.setup_save));
            save.setOnClickListener(v -> save());
            actions.addView(save, new LinearLayout.LayoutParams(0, s.dp(48), 1));
            controls.add(save);
            if (previous != null) {
                Button remove = s.flat(s.getString(R.string.setup_remove));
                remove.setTextColor(BAD);
                remove.setBackground(s.stroke(PAGE, BAD, 10));
                remove.setOnClickListener(v -> remove());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, s.dp(48), 1);
                lp.setMargins(s.dp(12), 0, 0, 0);
                actions.addView(remove, lp);
                controls.add(remove);
            }
            panel.addView(actions, s.matchWrap(0, s.dp(18), 0, 0));
            guide(side);
            if (previous != null && !secrets.isEmpty()) markHeld();
        }

        private boolean brand(String name) {
            JSONArray brands = pkg.optJSONArray("brands");
            for (int i = 0; brands != null && i < brands.length(); i++)
                if (name.equalsIgnoreCase(brands.optString(i))) return true;
            return false;
        }

        /** The Meross account's key, read once (DECISIONS 7); kept while the form is open. */
        private String accountKey;
        /** The account's devices (uuid, name, model, online), then what became of each. */
        private JSONArray accountDevices = new JSONArray();
        private final Map<String, String> imported = new HashMap<>();
        private boolean importing;
        private boolean askCode;

        /**
         * The key of the Meross account, read from the account as meross_lan
         * does: the same for every plug of the account. Copied, for the
         * first plug of « Ajouter un appareil »; Moli reuses it for the next
         * ones. The password is not kept.
         */
        private View merossAccount() {
            IntegrationsActivity s = screen;
            LinearLayout box = s.card();
            box.addView(s.text(s.getString(R.string.meross_title), 17, INK, true));
            box.addView(s.text(s.getString(R.string.meross_explained), 14, MUTED, false), s.matchWrap(0, s.dp(4), 0, 0));
            if (accountKey != null) { showKey(box); return box; }
            EditText email = s.field(s.getString(R.string.example_email), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            EditText password = s.field("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            EditText code = s.field("", InputType.TYPE_CLASS_NUMBER);
            labelled(box, s.getString(R.string.meross_email), null, email);
            labelled(box, s.getString(R.string.meross_password), s.getString(R.string.meross_password_help), password);
            LinearLayout codeRow = s.column();
            codeRow.setVisibility(askCode ? View.VISIBLE : View.GONE);
            box.addView(codeRow);
            labelled(codeRow, s.getString(R.string.meross_code), s.getString(R.string.meross_code_help), code);
            TextView why = s.text("", 14, BAD, false);
            why.setVisibility(View.GONE);
            box.addView(why, s.matchWrap(0, s.dp(10), 0, 0));
            Button get = s.primary(s.getString(R.string.meross_get));
            box.addView(get, s.matchWrap(0, s.dp(14), 0, 0));
            get.setOnClickListener(v -> {
                String mail = email.getText().toString().trim(), secret = password.getText().toString();
                if (mail.isEmpty() || secret.isEmpty()) { why.setText(R.string.meross_missing); why.setVisibility(View.VISIBLE); return; }
                get.setEnabled(false);
                get.setText(R.string.meross_getting);
                why.setVisibility(View.GONE);
                String typedCode = codeRow.getVisibility() == View.VISIBLE ? code.getText().toString() : null;
                s.probe.execute(() -> {
                    String failure = null;
                    JSONObject answer = null;
                    try { answer = Setup.merossKey(s, mail, secret, typedCode); }
                    catch (Exception e) { failure = s.getString(R.string.meross_unreachable, String.valueOf(e.getMessage())); }
                    JSONObject got = answer;
                    String error = failure;
                    s.handler.post(() -> {
                        if (s.isDestroyed()) return;
                        get.setEnabled(true);
                        get.setText(R.string.meross_get);
                        String key = got == null ? null : got.optString("key", null);
                        if (key != null && !key.isEmpty()) {
                            accountKey = key;
                            JSONArray listed = got.optJSONArray("devices");
                            if (listed != null) accountDevices = listed;
                            password.setText("");
                            copy(key);
                            box.removeAllViews();
                            box.addView(s.text(s.getString(R.string.meross_title), 17, INK, true));
                            showKey(box);
                            return;
                        }
                        int status = got == null ? 0 : got.optInt("status");
                        if (status == 1033 || status == 1032) { askCode = true; codeRow.setVisibility(View.VISIBLE); code.requestFocus(); }
                        why.setText(error != null ? error : refusal(status, got.optString("info")));
                        why.setVisibility(View.VISIBLE);
                    });
                });
            });
            return box;
        }

        private void showKey(LinearLayout box) {
            IntegrationsActivity s = screen;
            box.addView(s.text(s.getString(R.string.meross_got), 14, OK, true), s.matchWrap(0, s.dp(8), 0, 0));
            TextView key = s.text(accountKey, 16, INK, false);
            key.setTypeface(Typeface.MONOSPACE);
            key.setTextIsSelectable(true);
            key.setPadding(s.dp(12), s.dp(10), s.dp(12), s.dp(10));
            key.setBackground(s.stroke(PAGE, LINE, 10));
            box.addView(key, s.matchWrap(0, s.dp(8), 0, 0));
            box.addView(s.text(s.getString(R.string.meross_next), 14, INK, false), s.matchWrap(0, s.dp(10), 0, 0));
            LinearLayout actions = s.row();
            Button again = s.flat(s.getString(R.string.meross_copy));
            again.setOnClickListener(v -> copy(accountKey));
            actions.addView(again, new LinearLayout.LayoutParams(0, s.dp(48), 1));
            Button go = s.primary(s.getString(R.string.setup_open_add));
            go.setOnClickListener(v -> s.openDashboard("#/ajouter"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, s.dp(48), 1);
            lp.setMargins(s.dp(12), 0, 0, 0);
            actions.addView(go, lp);
            box.addView(actions, s.matchWrap(0, s.dp(12), 0, 0));
            if (accountDevices.length() > 0) plugs(box);
        }

        /**
         * The account's plugs, added in one go: found on the network by
         * their uuid, added by « Ajouter un appareil » with their Meross name.
         */
        private void plugs(LinearLayout box) {
            IntegrationsActivity s = screen;
            box.addView(s.text(s.getString(R.string.meross_plugs, accountDevices.length()), 16, INK, true), s.matchWrap(0, s.dp(18), 0, 0));
            box.addView(s.text(s.getString(R.string.meross_plugs_help), 14, MUTED, false), s.matchWrap(0, s.dp(4), 0, 0));
            LinearLayout list = s.column();
            box.addView(list, s.matchWrap(0, s.dp(6), 0, 0));
            Runnable fill = () -> {
                list.removeAllViews();
                for (int i = 0; i < accountDevices.length(); i++) {
                    JSONObject d = accountDevices.optJSONObject(i);
                    LinearLayout line = s.row();
                    line.setPadding(0, s.dp(6), 0, s.dp(6));
                    String model = d.optString("model");
                    TextView name = s.text(d.optString("name") + (model.isEmpty() ? "" : "  ·  " + model), 15, INK, false);
                    line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    String outcome = imported.get(d.optString("uuid"));
                    if (outcome != null) {
                        boolean good = outcome.equals("added") || outcome.equals("already");
                        line.addView(s.text(words(outcome, d.optBoolean("online", true)), 13, good ? OK : WARN, true));
                    }
                    list.addView(line);
                }
            };
            fill.run();
            TextView progress = s.text("", 14, INFO, false);
            progress.setVisibility(importing ? View.VISIBLE : View.GONE);
            box.addView(progress, s.matchWrap(0, s.dp(8), 0, 0));
            Button add = s.primary(s.getString(R.string.meross_add_all));
            add.setEnabled(!importing);
            box.addView(add, s.matchWrap(0, s.dp(10), 0, 0));
            add.setOnClickListener(v -> {
                importing = true;
                add.setEnabled(false);
                progress.setText(R.string.meross_finding);
                progress.setVisibility(View.VISIBLE);
                String key = accountKey;
                JSONArray devices = accountDevices;
                s.probe.execute(() -> {
                    Map<String, String> outcomes = new HashMap<>();
                    String failure = null;
                    try {
                        JSONArray uuids = new JSONArray();
                        for (int i = 0; i < devices.length(); i++) uuids.put(devices.optJSONObject(i).optString("uuid"));
                        JSONObject where = Setup.merossFind(s, key, uuids);
                        JSONArray plugs = new JSONArray();
                        Map<String, String> uuidAt = new HashMap<>();
                        for (int i = 0; i < devices.length(); i++) {
                            JSONObject d = devices.optJSONObject(i);
                            String uuid = d.optString("uuid").toLowerCase(Locale.ROOT);
                            String host = where.optString(uuid, null);
                            if (host == null) { outcomes.put(d.optString("uuid"), "missing"); continue; }
                            uuidAt.put(host, d.optString("uuid"));
                            plugs.put(new JSONObject().put("host", host).put("name", d.optString("name")));
                        }
                        if (plugs.length() > 0) {
                            s.handler.post(() -> progress.setText(R.string.meross_adding));
                            JSONObject added = Setup.merossAdd(s, key, plugs);
                            for (Map.Entry<String, String> e : uuidAt.entrySet()) {
                                JSONObject o = added.optJSONObject(e.getKey());
                                outcomes.put(e.getValue(), o == null ? "refused" : o.optString("outcome", "refused"));
                            }
                        }
                    } catch (Exception e) {
                        failure = String.valueOf(e.getMessage());
                    }
                    String why = failure;
                    s.handler.post(() -> {
                        importing = false;
                        if (s.isDestroyed()) return;
                        imported.putAll(outcomes);
                        fill.run();
                        add.setEnabled(true);
                        if (why != null) { progress.setText(s.getString(R.string.meross_import_failed, why)); progress.setTextColor(BAD); }
                        else progress.setVisibility(View.GONE);
                    });
                });
            });
        }

        /** What became of a plug, in words. */
        private String words(String outcome, boolean online) {
            switch (outcome) {
                case "added": return screen.getString(R.string.meross_added);
                case "already": return screen.getString(R.string.meross_already);
                case "missing": return screen.getString(online ? R.string.meross_missing_plug : R.string.meross_offline);
                case "unreachable": return screen.getString(R.string.meross_moli_silent);
                default: return screen.getString(R.string.meross_add_refused, outcome);
            }
        }

        private void copy(String key) {
            screen.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Meross", key));
            android.widget.Toast.makeText(screen, R.string.meross_copied, android.widget.Toast.LENGTH_SHORT).show();
        }

        /** Meross's refusal in words (statuses as meross_lan names them). */
        private String refusal(int status, String info) {
            switch (status) {
                case 1001: case 1002: case 1004: case 1005: case 1006: case 1008: case 1031:
                    return screen.getString(R.string.meross_wrong);
                case 1033: return screen.getString(R.string.meross_code_needed);
                case 1032: return screen.getString(R.string.meross_code_wrong);
                case 1028: return screen.getString(R.string.meross_too_often);
                case 1035: return screen.getString(R.string.meross_locked);
                default: return screen.getString(R.string.meross_refused, status, info);
            }
        }

        private List<String> names(String key) {
            List<String> out = new ArrayList<>();
            JSONArray list = pkg.optJSONArray(key);
            // A name to complete (`key-<device_id>`) goes through « another secret ».
            for (int i = 0; list != null && i < list.length(); i++)
                if (!list.optString(i).contains("<")) out.add(list.optString(i));
            return out;
        }

        /** The integration's id, made free in this house (`-2`, `-3`…). */
        private String freeId() {
            String base = pkg.optString("id");
            String id = base;
            for (int n = 2; screen.taken(id); n++) id = base + "-" + n;
            return id;
        }

        private EditText input(LinearLayout panel, String name, String value, boolean secret) {
            int type = secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            EditText e = screen.field(screen.example(name), type);
            e.setText(value);
            String help = secret ? screen.getString(R.string.setup_secret_help) : null;
            labelled(panel, screen.label(name), help, e);
            return e;
        }

        private void labelled(LinearLayout panel, String label, String help, EditText input) {
            panel.addView(screen.text(label, 14, INK, true), screen.matchWrap(0, screen.dp(12), 0, screen.dp(4)));
            panel.addView(input, screen.matchWrap(0, 0, 0, 0));
            if (help != null) panel.addView(screen.text(help, 12, MUTED, false), screen.matchWrap(0, screen.dp(3), 0, 0));
            controls.add(input);
        }

        /** A secret already in the vault: kept unless a new value is typed. */
        private void markHeld() {
            JSONArray names = new JSONArray(names("secrets"));
            screen.work.execute(() -> {
                try {
                    JSONArray in = Setup.stored(screen, previous, names);
                    screen.handler.post(() -> {
                        for (int i = 0; i < in.length(); i++) {
                            EditText e = secrets.get(in.optString(i));
                            if (e != null) e.setHint(R.string.setup_secret_held);
                        }
                    });
                } catch (Exception unreadable) { /* the fields stay empty */ }
            });
        }

        void lock(boolean on) { for (View v : controls) v.setEnabled(!on); if (id != null && previous != null) id.setEnabled(false); }

        private void save() {
            IntegrationsActivity s = screen;
            String instance = id.getText().toString().trim();
            if (instance.isEmpty() || instance.contains(":") || instance.contains("/")) { id.setError(s.getString(R.string.setup_instance_invalid)); return; }
            if (!instance.equals(previous) && s.taken(instance)) { id.setError(s.getString(R.string.setup_instance_taken)); return; }
            try {
                JSONObject change = new JSONObject()
                        .put("id", instance)
                        .put("integration", pkg.optString("id"))
                        .put("enabled", enabled.isChecked())
                        .put("fields", values(fields))
                        .put("vars", values(vars))
                        .put("extra", extra.getText().toString());
                if (previous != null) change.put("previous", previous);
                JSONObject typed = values(secrets);
                String other = otherName.getText().toString().trim();
                if (!other.isEmpty() && otherValue.getText().length() > 0) typed.put(other, otherValue.getText().toString());
                for (java.util.Iterator<String> k = typed.keys(); k.hasNext(); )
                    if (typed.optString(k.next()).trim().isEmpty()) k.remove();
                s.apply(instance, () -> Setup.put(s, change, instance, typed));
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private void remove() {
            IntegrationsActivity s = screen;
            JSONArray names = new JSONArray(names("secrets"));
            s.apply(null, () -> Setup.remove(s, previous, names));
        }

        private static JSONObject values(Map<String, EditText> inputs) throws org.json.JSONException {
            JSONObject out = new JSONObject();
            for (Map.Entry<String, EditText> e : inputs.entrySet()) out.put(e.getKey(), e.getValue().getText().toString());
            return out;
        }

        /** The official guide of the package, under the form. */
        private void guide(LinearLayout side) {
            String text = pkg.optString("onboarding");
            if (text.isEmpty()) return;
            LinearLayout box = screen.card();
            box.setBackground(screen.stroke(PAGE, LINE, 14));
            box.addView(screen.text(screen.getString(R.string.setup_guide), 14, MUTED, true));
            TextView body = screen.text("", 14, INK, false);
            body.setText(screen.markdown(text));
            body.setLineSpacing(0, 1.15f);
            body.setTextIsSelectable(true);
            box.addView(body, screen.matchWrap(0, screen.dp(6), 0, 0));
            side.addView(box);
        }
    }
}
