package io.moli.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The management screen: Moli's state, start/stop, integrations, the dashboard. */
public final class ManagementActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService probe = Executors.newSingleThreadExecutor();
    private volatile boolean healthy;
    private TextView state;
    private Button power, open;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean running = NativeServer.running();
            int port = MoliService.port;
            if (running) probe.execute(() -> healthy = healthy(port)); else healthy = false;
            String failure = MoliService.refused != null ? MoliService.refused : NativeServer.failure();
            if (MoliService.stopping) state.setText(R.string.state_stopping);
            else if (running && healthy) state.setText(getString(R.string.state_running, port));
            else if (running || MoliService.starting) state.setText(R.string.state_starting);
            else if (failure != null) state.setText(getString(R.string.state_failed, failure));
            else state.setText(R.string.state_stopped);
            int tint = MoliService.stopping || running && !healthy || MoliService.starting ? WARN
                    : running ? OK : failure != null ? BAD : MUTED;
            dot.setBackground(shape(tint, 0, 7));
            boolean on = running || MoliService.starting;
            power.setText(on ? R.string.stop : R.string.start);
            power.setEnabled(!MoliService.stopping);
            open.setEnabled(running && healthy);
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

    private static final int INK = 0xFF1B2733, MUTED = 0xFF5C6B7A, PAGE = 0xFFF2F4F7, CARD = 0xFFFFFFFF,
            ACCENT = 0xFF234E70, LINE = 0xFFDDE3EA, OK = 0xFF2E7D4F, WARN = 0xFFB26A00, BAD = 0xFFB3261E;
    private View dot;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable shape(int fill, int line, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (line != 0) g.setStroke(dp(2), line);
        return g;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(shape(CARD, 0, 16));
        return c;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(top), 0, 0);
        return p;
    }

    /** A card that opens something: its title and one line saying what it is. */
    private LinearLayout tile(int title, int help, Runnable action) {
        LinearLayout c = card();
        c.addView(text(getString(title), 17, INK, true));
        c.addView(text(getString(help), 13, MUTED, false), gap(2));
        c.setOnClickListener(v -> action.run());
        return c;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));

        LinearLayout head = card();
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(this);
        line.addView(dot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, dp(12), 0);
        titles.addView(text(getString(R.string.app_name), 24, INK, true));
        state = text("", 14, MUTED, false);
        titles.addView(state);
        line.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        power = new Button(this);
        power.setAllCaps(false);
        power.setTextColor(ACCENT);
        power.setBackground(shape(PAGE, ACCENT, 10));
        power.setOnClickListener(v -> togglePower());
        line.addView(power);
        head.addView(line);
        root.addView(head);

        open = new Button(this);
        open.setText(R.string.open_dashboard);
        open.setAllCaps(false);
        open.setTextSize(20);
        open.setTextColor(Color.WHITE);
        open.setBackground(shape(ACCENT, 0, 16));
        open.setOnClickListener(v -> startActivity(new Intent(this, DashboardActivity.class)));
        LinearLayout.LayoutParams big = gap(12);
        big.height = dp(72);
        root.addView(open, big);

        LinearLayout tiles = new LinearLayout(this);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        tiles.addView(tile(R.string.integrations, R.string.integrations_help, () ->
                startActivity(new Intent(this, IntegrationsActivity.class))), half);
        LinearLayout.LayoutParams other = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        other.setMargins(dp(12), 0, 0, 0);
        tiles.addView(tile(R.string.network, R.string.network_help, () ->
                startActivity(new Intent(this, NetworkActivity.class))), other);
        root.addView(tiles, gap(12));

        // The house's settings, one screen each (ETAT, mission 3).
        LinearLayout settings = card();
        settings.addView(text(getString(R.string.house_settings), 15, MUTED, true));
        settings.addView(link(R.string.energy, () -> startActivity(new Intent(this, EnergyActivity.class))), gap(6));
        root.addView(settings, gap(12));

        LinearLayout house = card();
        house.addView(text(getString(R.string.house_title), 15, MUTED, true));
        house.addView(link(R.string.backup, this::chooseBackup), gap(6));
        house.addView(link(R.string.restore, this::chooseRestore), gap(0));
        house.addView(link(R.string.key_title, this::showKey), gap(0));
        root.addView(house, gap(12));

        LinearLayout launch = card();
        Switch direct = new Switch(this);
        direct.setText(R.string.direct_launch);
        direct.setTextSize(16);
        direct.setTextColor(INK);
        direct.setChecked(directLaunch(this));
        direct.setOnCheckedChangeListener((b, on) ->
                getSharedPreferences("moli", MODE_PRIVATE).edit().putBoolean(DIRECT, on).apply());
        launch.addView(direct);
        launch.addView(text(getString(R.string.direct_launch_help), 13, MUTED, false), gap(4));
        root.addView(launch, gap(12));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAGE);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);

        // « Ouvrir Moli au lancement »: a fresh launch goes straight to the
        // dashboard; Back from its first page lands here, on this screen.
        if (saved == null && directLaunch(this) && ProtectedKey.exists(this))
            startActivity(new Intent(this, DashboardActivity.class));
    }

    static final String DIRECT = "direct_launch";

    static boolean directLaunch(android.content.Context context) {
        return context.getSharedPreferences("moli", MODE_PRIVATE).getBoolean(DIRECT, false);
    }

    private Button link(int label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setTextColor(ACCENT);
        b.setBackgroundColor(0);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private static final int SAVE = 1, RESTORE = 2;

    private void chooseBackup() {
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date());
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
                handler.post(() -> Toast.makeText(this, ok ? R.string.backup_done : R.string.backup_failed, Toast.LENGTH_LONG).show());
            });
        } else if (request == RESTORE) {
            askRestoreKey(uri);
        }
    }

    /** A backup holds no key: the one it was kept with, and the house code, are typed here. */
    private void askRestoreKey(Uri uri) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText master = new EditText(this);
        master.setHint(R.string.restore_key_field);
        master.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText pin = new EditText(this);
        pin.setHint(R.string.pin_title);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(master);
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

    /** The master key and the house code, to keep apart (NordPass): a backup never holds them. */
    private void showKey() {
        if (!ProtectedKey.exists(this)) return;
        String[] key;
        try { key = ProtectedKey.open(this); } catch (Exception failure) {
            Toast.makeText(this, R.string.key_failed, Toast.LENGTH_LONG).show();
            return;
        }
        TextView text = new TextView(this);
        int gap = (int) (16 * getResources().getDisplayMetrics().density);
        text.setPadding(gap, gap, gap, 0);
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

    private Button button(LinearLayout parent, int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
        return button;
    }

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
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pin_title)
                .setMessage(R.string.pin_message)
                .setView(pin)
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

    @Override protected void onStart() { super.onStart(); handler.post(refresh); }
    @Override protected void onStop() { handler.removeCallbacks(refresh); super.onStop(); }
    @Override protected void onDestroy() { probe.shutdownNow(); super.onDestroy(); }
}
