package io.moli.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
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
    private static boolean healthy(int port) {
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
        setContentView(root);
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
        }));
        dialog.show();
    }

    @Override protected void onStart() { super.onStart(); handler.post(refresh); }
    @Override protected void onStop() { handler.removeCallbacks(refresh); super.onStop(); }
    @Override protected void onDestroy() { probe.shutdownNow(); super.onDestroy(); }
}
