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
import android.widget.Button;
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

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int gap = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(gap, gap, gap, gap);
        state = new TextView(this);
        state.setTextSize(20);
        state.setPadding(0, 0, 0, gap);
        root.addView(state);
        power = button(root, R.string.start, this::togglePower);
        open = button(root, R.string.open_dashboard, () ->
                startActivity(new Intent(this, DashboardActivity.class)));
        button(root, R.string.integrations, () ->
                startActivity(new Intent(this, IntegrationsActivity.class)));
        button(root, R.string.backup, this::chooseBackup);
        button(root, R.string.restore, this::chooseRestore);
        button(root, R.string.key_title, this::showKey);
        setContentView(root);
        // A house already set up opens straight on Moli; this screen stays
        // one Back away.
        if (saved == null && ProtectedKey.exists(this))
            startActivity(new Intent(this, DashboardActivity.class));
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
