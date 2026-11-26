package io.moli.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * « Recherche réseau »: the devices of the home network (network.rs), with
 * their address, name, maker and model where they say it. Touching a row
 * copies its address.
 */
public final class NetworkActivity extends Activity {
    private static final int INK = 0xFF1B2733, MUTED = 0xFF5C6B7A, PAGE = 0xFFF2F4F7,
            ACCENT = 0xFF234E70, BAD = 0xFFB3261E;

    /** The last search, kept while the app runs (leaving the screen loses nothing). */
    private static JSONArray last;
    private static long lastAt;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button search;
    private TableLayout table;

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().getDecorView().setBackgroundColor(PAGE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(24));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        search = new Button(this);
        search.setText(R.string.network_search);
        search.setAllCaps(false);
        search.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(dp(10));
        search.setBackground(g);
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setOnClickListener(v -> run());
        top.addView(search);
        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(MUTED);
        status.setPadding(dp(16), 0, 0, 0);
        top.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(top);
        table = new TableLayout(this);
        table.setPadding(0, dp(12), 0, 0);
        HorizontalScrollView wide = new HorizontalScrollView(this);
        wide.addView(table);
        root.addView(wide);
        ScrollView page = new ScrollView(this);
        page.addView(root);
        setContentView(page);
        if (last == null) run(); else show();
    }

    private void run() {
        search.setEnabled(false);
        status.setTextColor(MUTED);
        status.setText(R.string.network_searching);
        work.execute(() -> {
            JSONArray found = null;
            String failure = null;
            try { found = Setup.networkScan(this); } catch (Exception e) { failure = e.getMessage(); }
            JSONArray result = found;
            String why = failure;
            handler.post(() -> {
                if (isDestroyed()) return;
                search.setEnabled(true);
                if (result == null) {
                    status.setTextColor(BAD);
                    status.setText(getString(R.string.network_failed, why == null ? "?" : why));
                    return;
                }
                last = result;
                lastAt = System.currentTimeMillis();
                show();
            });
        });
    }

    private void show() {
        String when = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(lastAt));
        status.setTextColor(MUTED);
        status.setText(getResources().getQuantityString(R.plurals.network_found, last.length(), last.length(), when));
        table.removeAllViews();
        TableRow head = new TableRow(this);
        int[] titles = { R.string.network_col_address, R.string.network_col_name, R.string.network_col_maker,
                R.string.network_col_model, R.string.network_col_mac };
        for (int t : titles) head.addView(cell(getString(t), MUTED, true));
        table.addView(head);
        for (int i = 0; i < last.length(); i++) table.addView(row(last.optJSONObject(i), i));
    }

    private TableRow row(JSONObject d, int i) {
        TableRow r = new TableRow(this);
        if (i % 2 == 0) r.setBackgroundColor(Color.WHITE);
        String ip = d.optString("ip");
        String role = d.isNull("role") ? "" : d.optString("role");
        String name = value(d, "name");
        if (role.equals("tablet")) name = join(getString(R.string.network_role_tablet), name);
        else if (role.equals("box")) name = join(getString(R.string.network_role_box), name);
        TextView address = cell(ip, ACCENT, true);
        address.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        r.addView(address);
        r.addView(cell(name.isEmpty() ? "—" : name, name.isEmpty() ? MUTED : INK, false));
        r.addView(cell(orDash(value(d, "maker")), INK, false));
        r.addView(cell(orDash(value(d, "model")), INK, false));
        TextView mac = cell(orDash(value(d, "mac")), MUTED, false);
        mac.setTypeface(Typeface.MONOSPACE);
        r.addView(mac);
        r.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("IP", ip));
            Toast.makeText(this, getString(R.string.network_copied, ip), Toast.LENGTH_SHORT).show();
        });
        return r;
    }

    private static String value(JSONObject d, String key) { return d.isNull(key) ? "" : d.optString(key); }
    private static String orDash(String v) { return v.isEmpty() ? "—" : v; }
    private static String join(String role, String name) { return name.isEmpty() ? role : role + " · " + name; }

    private TextView cell(String value, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(bold && color == MUTED ? 13 : 15);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(10), dp(10), dp(14), dp(10));
        t.setBackgroundColor(Color.TRANSPARENT);
        return t;
    }

    @Override protected void onDestroy() { work.shutdownNow(); super.onDestroy(); }
}
