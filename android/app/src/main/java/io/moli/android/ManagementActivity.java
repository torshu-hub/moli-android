package io.moli.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The home screen of the app (android/REFONTE.md): where Moli stands and
 * how to reach it, then every screen of the house by group, each tile saying
 * in one line where things stand.
 */
public final class ManagementActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService probe = Executors.newSingleThreadExecutor();
    private final Look ui = new Look(this);
    private volatile boolean healthy;
    /** Moli answered at the last look: the summaries are read again when it starts answering. */
    private boolean answered;
    private int ticks;
    private TextView state, address;
    private View dot;
    private Button power, open;
    /** The tiles' lines that Moli and the files fill, and what they last said (kept when the page is rebuilt). */
    private TextView backupLine;
    private final java.util.Map<String, TextView> lines = new java.util.HashMap<>();
    private final java.util.Map<String, String> texts = new java.util.HashMap<>();

    /** A tile that opens a screen, its line filled by `readSummaries` (`first` until then). */
    private View screen(String key, int icon, int[] hue, int title, Class<?> screen, String first) {
        TextView line = summary(texts.containsKey(key) ? texts.get(key) : first);
        lines.put(key, line);
        return ui.tile(icon, hue, getString(title), line, () -> startActivity(new Intent(this, screen)));
    }

    private TextView summary(String words) {
        TextView t = new TextView(this);
        t.setText(words);
        return t;
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean running = NativeServer.running();
            int port = MoliService.port;
            if (running) probe.execute(() -> healthy = healthy(port)); else healthy = false;
            boolean serving = running && healthy;
            String failure = MoliService.refused != null ? MoliService.refused : NativeServer.failure();
            if (MoliService.stopping) state.setText(R.string.state_stopping);
            else if (serving) state.setText(R.string.state_running);
            else if (running || MoliService.starting) state.setText(R.string.state_starting);
            else if (failure != null) state.setText(getString(R.string.state_failed, failure));
            else state.setText(R.string.state_stopped);
            int tint = MoliService.stopping || running && !healthy || MoliService.starting ? Look.WARN
                    : running ? Look.OK : failure != null ? Look.BAD : Look.MUTED;
            dot.setBackground(ui.round(tint, 6));
            boolean on = running || MoliService.starting;
            power.setText(on ? R.string.stop : R.string.start);
            power.setEnabled(!MoliService.stopping);
            open.setEnabled(serving);
            open.setAlpha(serving ? 1f : 0.5f);
            String here = serving ? Setup.homeAddress(ManagementActivity.this) : null;
            address.setVisibility(here != null ? View.VISIBLE : View.GONE);
            if (here != null) address.setText(getString(R.string.home_address, "http://" + here + ":" + port));
            // Read again when Moli starts or stops answering, then every 10 s while it does.
            if (serving != answered || serving && ++ticks % 10 == 0) { answered = serving; readSummaries(); }
            handler.postDelayed(this, 1000);
        }
    };

    /** The official health route answers: Moli serves. */
    static boolean healthy(int port) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/health").openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            try { return connection.getResponseCode() == 200; } finally { connection.disconnect(); }
        } catch (Exception unreachable) {
            return false;
        }
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        build();
        // « Ouvrir Moli au lancement »: a fresh launch goes straight to the
        // dashboard; Back from its first page lands here, on this screen.
        if (saved == null && directLaunch(this) && ProtectedKey.exists(this))
            startActivity(new Intent(this, DashboardActivity.class));
    }

    /** Turned: the tiles take the new width. */
    @Override public void onConfigurationChanged(Configuration changed) {
        super.onConfigurationChanged(changed);
        build();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        readSummaries();
    }

    // ---- The page ----

    private void build() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        int side = ui.dp(wide ? 32 : 16);
        LinearLayout page = ui.column();
        page.setPadding(side, ui.dp(wide ? 28 : 16), side, ui.dp(32));

        page.addView(header(wide));

        int columns = ui.columns();
        backupLine = summary(lastBackup());
        page.addView(ui.group(getString(R.string.group_devices)));
        List<View> devices = new ArrayList<>();
        devices.add(screen("integrations", R.drawable.ic_extension, Look.BLUE, R.string.integrations, IntegrationsActivity.class, getString(R.string.integrations_help)));
        devices.add(ui.tile(R.drawable.ic_wifi_find, Look.TEAL, getString(R.string.network), line(R.string.network_help),
                () -> startActivity(new Intent(this, NetworkActivity.class))));
        page.addView(ui.grid(devices, columns));

        page.addView(ui.group(getString(R.string.group_house)));
        List<View> house = new ArrayList<>();
        house.add(screen("home", R.drawable.ic_weekend, Look.AMBER, R.string.home_screen, HomeActivity.class, ""));
        house.add(screen("heating", R.drawable.ic_thermostat, Look.AMBER, R.string.heating_screen, HeatingActivity.class, ""));
        house.add(screen("lights", R.drawable.ic_lightbulb, Look.AMBER, R.string.lights_screen, LightsActivity.class, ""));
        house.add(screen("scenes", R.drawable.ic_palette, Look.AMBER, R.string.scenes_screen, ScenesActivity.class, ""));
        house.add(screen("energy", R.drawable.ic_bolt, Look.AMBER, R.string.energy, EnergyActivity.class, ""));
        page.addView(ui.grid(house, columns));

        page.addView(ui.group(getString(R.string.group_tablet)));
        List<View> tablet = new ArrayList<>();
        tablet.add(ui.tile(R.drawable.ic_download, Look.SLATE, getString(R.string.backup), backupLine, this::chooseBackup));
        tablet.add(ui.tile(R.drawable.ic_upload, Look.SLATE, getString(R.string.restore), line(R.string.restore_help), this::chooseRestore));
        tablet.add(ui.tile(R.drawable.ic_key, Look.SLATE, getString(R.string.key_title), line(R.string.key_help), this::showKey));
        tablet.add(ui.tile(R.drawable.ic_receipt_long, Look.SLATE, getString(R.string.journal_title), line(R.string.journal_help),
                () -> Journal.show(this)));
        tablet.add(directLaunchTile());
        page.addView(ui.grid(tablet, columns));

        // Wide screens: the page keeps a readable width, centred.
        LinearLayout frame = new LinearLayout(this);
        frame.setGravity(Gravity.CENTER_HORIZONTAL);
        frame.addView(page, new LinearLayout.LayoutParams(Math.min(ui.dp(1200), getResources().getDisplayMetrics().widthPixels),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.addView(frame);
        ui.edgeToEdge(scroll);
        setContentView(scroll);
    }

    private TextView line(int words) { return summary(getString(words)); }

    /** Moli's name, its state, where the other screens of the house reach it, and the way in. */
    private View header(boolean wide) {
        LinearLayout card = ui.column();
        card.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18));
        card.setBackground(ui.round(Look.CARD, 18));
        card.setElevation(ui.dp(1));

        LinearLayout top = ui.row();
        LinearLayout titles = ui.column();
        titles.addView(ui.text(getString(R.string.app_name), 28, Look.INK, true));
        LinearLayout status = ui.row();
        dot = new View(this);
        status.addView(dot, new LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)));
        state = ui.text("", 15, Look.INK, false);
        state.setPadding(ui.dp(8), 0, 0, 0);
        status.addView(state, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        titles.addView(status, ui.matchWrap(0, ui.dp(4), 0, 0));
        address = ui.text("", 13, Look.MUTED, false);
        address.setVisibility(View.GONE);
        address.setOnClickListener(v -> {
            String here = Setup.homeAddress(this);
            if (here == null) return;
            String url = "http://" + here + ":" + MoliService.port;
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Moli", url));
            Toast.makeText(this, getString(R.string.home_address_copied, url), Toast.LENGTH_SHORT).show();
        });
        titles.addView(address, ui.matchWrap(0, ui.dp(4), 0, 0));
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        power = ui.flat("");
        power.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_power_settings_new, Look.ACCENT), null, null, null);
        power.setCompoundDrawablePadding(ui.dp(6));
        power.setPadding(ui.dp(14), 0, ui.dp(16), 0);
        power.setOnClickListener(v -> togglePower());
        top.addView(power, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48)));

        open = ui.primary(getString(R.string.open_dashboard));
        open.setTextSize(18);
        open.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_dashboard, 0xFFFFFFFF), null, null, null);
        open.setCompoundDrawablePadding(ui.dp(10));
        open.setPadding(ui.dp(24), 0, ui.dp(28), 0);
        open.setBackground(ui.round(Look.ACCENT, 14));
        open.setOnClickListener(v -> startActivity(new Intent(this, DashboardActivity.class)));
        if (wide) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(60));
            p.setMargins(ui.dp(14), 0, 0, 0);
            top.addView(open, p);
            card.addView(top);
        } else {
            card.addView(top);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(60));
            p.setMargins(0, ui.dp(16), 0, 0);
            card.addView(open, p);
        }
        return card;
    }

    private android.graphics.drawable.Drawable tinted(int res, int color) {
        android.graphics.drawable.Drawable d = getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    /** « Ouvrir Moli au lancement »: a tile with its switch. */
    private View directLaunchTile() {
        Switch direct = new Switch(this);
        direct.setChecked(directLaunch(this));
        direct.setOnCheckedChangeListener((b, on) ->
                getSharedPreferences("moli", MODE_PRIVATE).edit().putBoolean(DIRECT, on).apply());
        LinearLayout tile = ui.tile(R.drawable.ic_rocket_launch, Look.SLATE, getString(R.string.direct_launch), line(R.string.direct_launch_help), null);
        tile.addView(direct);
        tile.setOnClickListener(v -> direct.toggle());
        return tile;
    }

    // ---- Summaries: one line per tile, from the files and from Moli ----

    private void readSummaries() {
        int port = MoliService.port;
        boolean serving = answered;
        probe.execute(() -> {
            java.util.Map<String, String> now = new java.util.HashMap<>();
            now.put("integrations", integrationsSummary(serving ? IntegrationsActivity.health(port) : null));
            now.put("home", homeSummary());
            now.put("heating", heatingSummary());
            now.put("lights", listSummary("fixture", R.plurals.lights_summary, null));
            now.put("scenes", listSummary("scene", R.plurals.scenes_summary, "rhythm"));
            now.put("energy", energySummary());
            handler.post(() -> {
                if (isDestroyed()) return;
                for (java.util.Map.Entry<String, String> e : now.entrySet()) {
                    if (e.getValue() == null || !lines.containsKey(e.getKey())) continue;
                    texts.put(e.getKey(), e.getValue());
                    lines.get(e.getKey()).setText(e.getValue());
                }
            });
        });
    }

    /** « 3 luminaires », « 2 scènes · rythme de la journée », or « Pas encore réglé ». */
    private String listSummary(String section, int plural, String also) {
        JSONObject sections;
        try { sections = also == null ? Setup.sections(this, section).optJSONObject("sections") : Setup.sections(this, section, also).optJSONObject("sections"); } catch (Exception unreadable) { return null; }
        JSONArray list = sections.optJSONArray(section);
        List<String> parts = new ArrayList<>();
        if (list != null && list.length() > 0) parts.add(getResources().getQuantityString(plural, list.length(), list.length()));
        if (also != null && !sections.isNull(also)) parts.add(getString(R.string.scenes_summary_rhythm));
        return parts.isEmpty() ? getString(R.string.not_set_yet) : String.join(" · ", parts);
    }

    /** « 3 intégrations · toutes répondent », « … · 1 ne répond pas », or none yet. */
    private String integrationsSummary(Map<String, JSONObject> live) {
        JSONArray drivers;
        try { drivers = Setup.drivers(this); } catch (Exception unreadable) { return getString(R.string.integrations_help); }
        int count = 0, silent = 0;
        for (int i = 0; i < drivers.length(); i++) {
            JSONObject d = drivers.optJSONObject(i);
            if (!d.optBoolean("enabled", true)) continue;
            count++;
            JSONObject status = live == null ? null : live.get(d.optString("id"));
            // Starting or waiting is not silent; only an integration in error or stopped is.
            String now = status == null ? "" : status.optString("state");
            if ("backoff".equals(now) || "stopped".equals(now)) silent++;
        }
        if (count == 0) return getString(R.string.integrations_none);
        String head = getResources().getQuantityString(R.plurals.integrations_count, count, count);
        if (live == null) return head;
        return head + " · " + (silent == 0 ? getString(R.string.integrations_all_fine)
                : getResources().getQuantityString(R.plurals.integrations_silent, silent, silent));
    }

    /** « 6 pièces · 2 étages », or « Pas encore réglé ». */
    private String homeSummary() {
        JSONObject layout;
        try { layout = Setup.home(this).optJSONObject("layout"); } catch (Exception unreadable) { return null; }
        JSONArray rooms = layout == null ? null : layout.optJSONArray("rooms");
        if (rooms == null || rooms.length() == 0) return getString(R.string.not_set_yet);
        java.util.Set<String> floors = new java.util.HashSet<>();
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject r = rooms.optJSONObject(i);
            if (r != null && !r.optString("floor").isEmpty()) floors.add(r.optString("floor"));
        }
        String out = getResources().getQuantityString(R.plurals.home_summary_rooms, rooms.length(), rooms.length());
        return floors.isEmpty() ? out : out + " · " + getResources().getQuantityString(R.plurals.home_summary_floors, floors.size(), floors.size());
    }

    /** « 4 pièces chauffées · 3 programmes », or « Pas encore réglé ». */
    private String heatingSummary() {
        JSONObject sections;
        try { sections = Setup.sections(this, "heating", "heating_house").optJSONObject("sections"); } catch (Exception unreadable) { return null; }
        JSONArray rooms = sections.optJSONArray("heating");
        if (rooms == null || rooms.length() == 0) return getString(R.string.not_set_yet);
        String out = getResources().getQuantityString(R.plurals.heating_summary_rooms, rooms.length(), rooms.length());
        JSONObject h = sections.optJSONObject("heating_house");
        JSONArray p = h == null ? null : h.optJSONArray("programmes");
        return p == null ? out : out + " · " + getResources().getQuantityString(R.plurals.heating_summary_programmes, p.length(), p.length());
    }

    /** « Heures pleines / creuses · 4 appareils suivis », or « Pas encore réglé ». */
    private String energySummary() {
        JSONObject section;
        try { section = Setup.energy(this).optJSONObject("section"); } catch (Exception unreadable) { return null; }
        if (section == null || section.length() == 0) return getString(R.string.not_set_yet);
        List<String> parts = new ArrayList<>();
        JSONObject tariff = section.optJSONObject("tariff");
        JSONObject prices = tariff == null ? null : tariff.optJSONObject("prices");
        if (prices != null && prices.length() > 1) parts.add(getString(R.string.energy_summary_peak));
        JSONArray meters = section.optJSONArray("meter");
        int followed = meters == null ? 0 : meters.length();
        if (followed > 0) parts.add(getResources().getQuantityString(R.plurals.energy_summary_followed, followed, followed));
        return parts.isEmpty() ? getString(R.string.energy_summary_some) : String.join(" · ", parts);
    }

    private String lastBackup() {
        long at = getSharedPreferences("moli", MODE_PRIVATE).getLong(LAST_BACKUP, 0);
        return at == 0 ? getString(R.string.backup_help)
                : getString(R.string.backup_last, DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(at)));
    }

    static final String DIRECT = "direct_launch", LAST_BACKUP = "last_backup";

    static boolean directLaunch(android.content.Context context) {
        return context.getSharedPreferences("moli", MODE_PRIVATE).getBoolean(DIRECT, false);
    }

    // ---- This tablet: backup, restore, the key ----

    private static final int SAVE = 1, RESTORE = 2;

    private void chooseBackup() {
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new Date());
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "moli-sauvegarde-" + day + ".zip"), SAVE);
    }

    private void chooseRestore() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), RESTORE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Uri uri = data == null ? null : data.getData();
        if (result != RESULT_OK || uri == null) return;
        if (request == SAVE) {
            probe.execute(() -> {
                boolean done;
                try { Backup.save(this, uri); done = true; } catch (Exception failure) { done = false; }
                boolean ok = done;
                if (ok) getSharedPreferences("moli", MODE_PRIVATE).edit().putLong(LAST_BACKUP, System.currentTimeMillis()).apply();
                handler.post(() -> {
                    Toast.makeText(this, ok ? R.string.backup_done : R.string.backup_failed, Toast.LENGTH_LONG).show();
                    backupLine.setText(lastBackup());
                });
            });
        } else if (request == RESTORE) {
            askRestoreKey(uri);
        }
    }

    /** A backup holds no key: the one it was kept with, and the house code, are typed here. */
    private void askRestoreKey(Uri uri) {
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
        EditText master = ui.field(getString(R.string.restore_key_field), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText pin = ui.field(getString(R.string.pin_title), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(master, ui.matchWrap(0, 0, 0, ui.dp(10)));
        box.addView(pin);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.restore)
                .setMessage(R.string.restore_message)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String key = master.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            String code = pin.getText().toString();
            if (!key.matches("[0-9a-f]{64}")) { master.setError(getString(R.string.restore_key_invalid)); return; }
            if (!code.matches("[0-9]{6,8}")) { pin.setError(getString(R.string.pin_invalid)); return; }
            dialog.dismiss();
            probe.execute(() -> {
                boolean done;
                try { Backup.restore(this, uri, key, code); done = true; } catch (Exception failure) { done = false; }
                boolean ok = done;
                handler.post(() -> Toast.makeText(this, ok ? R.string.restore_done : R.string.restore_failed, Toast.LENGTH_LONG).show());
            });
        }));
        dialog.show();
    }

    /** The master key and the house code, to keep apart (a password manager): a backup never holds them. */
    private void showKey() {
        if (!ProtectedKey.exists(this)) {
            Toast.makeText(this, R.string.key_none, Toast.LENGTH_LONG).show();
            return;
        }
        String[] key;
        try { key = ProtectedKey.open(this); } catch (Exception failure) {
            Toast.makeText(this, R.string.key_failed, Toast.LENGTH_LONG).show();
            return;
        }
        TextView text = new TextView(this);
        int gap = ui.dp(20);
        text.setPadding(gap, gap / 2, gap, 0);
        text.setTextIsSelectable(true);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setText(getString(R.string.key_master) + "\n" + key[0] + "\n\n" + getString(R.string.pin_title) + " : " + key[1]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.key_title)
                .setMessage(R.string.key_message)
                .setView(text)
                .setPositiveButton(R.string.key_copy, (d, w) -> {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Moli", key[0]));
                    Toast.makeText(this, R.string.key_copied, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    // ---- Moli: start, stop, a new house ----

    private void togglePower() {
        if (NativeServer.running() || MoliService.starting) {
            startForegroundService(new Intent(this, MoliService.class).setAction(MoliService.STOP));
        } else if (ProtectedKey.exists(this)) {
            MoliService.refused = null;
            startForegroundService(new Intent(this, MoliService.class));
        } else {
            choosePin();
        }
    }

    /** A new house: its code, chosen once (no terminal here to read an installation code). */
    private void choosePin() {
        EditText pin = ui.field(getString(R.string.pin_title), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
        box.addView(pin);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pin_title)
                .setMessage(R.string.pin_message)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = pin.getText().toString();
            if (!value.matches("[0-9]{6,8}")) { pin.setError(getString(R.string.pin_invalid)); return; }
            try {
                ProtectedKey.create(this, value);
            } catch (Exception failure) {
                MoliService.refused = getString(R.string.key_failed);
                dialog.dismiss();
                return;
            }
            dialog.dismiss();
            togglePower();
            startActivity(new Intent(this, DashboardActivity.class));
        }));
        dialog.show();
    }

    @Override protected void onStart() {
        super.onStart();
        handler.post(refresh);
        readSummaries();
    }

    @Override protected void onStop() { handler.removeCallbacks(refresh); super.onStop(); }
    @Override protected void onDestroy() { probe.shutdownNow(); super.onDestroy(); }
}
