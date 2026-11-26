package io.moli.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The official Moli dashboard, served by the engine on this tablet, full
 * screen. It waits for Moli to answer; Back at its first page returns to the
 * management screen.
 */
public final class DashboardActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService probe = Executors.newSingleThreadExecutor();
    private WebView web;
    private TextView waiting;
    private boolean loaded;

    private final Runnable wait = new Runnable() {
        @Override public void run() {
            int port = MoliService.port;
            probe.execute(() -> {
                boolean up = NativeServer.running() && ManagementActivity.healthy(port);
                handler.post(() -> {
                    if (isDestroyed() || loaded) return;
                    if (up) {
                        loaded = true;
                        waiting.setVisibility(View.GONE);
                        web.setVisibility(View.VISIBLE);
                        web.loadUrl("http://127.0.0.1:" + port + "/");
                    } else {
                        handler.postDelayed(this, 500);
                    }
                });
            });
        }
    };

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("127.0.0.1".equals(uri.getHost())) return false;
                // Elsewhere (a manufacturer's page…): the browser.
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }
        });
        waiting = new TextView(this);
        waiting.setText(R.string.state_starting);
        waiting.setTextSize(22);
        waiting.setGravity(android.view.Gravity.CENTER);
        root.addView(web);
        root.addView(waiting);
        setContentView(root);
        if (saved != null && web.restoreState(saved) != null) {
            loaded = true;
            waiting.setVisibility(View.GONE);
        } else {
            web.setVisibility(View.INVISIBLE);
            MoliService.start(this);
            handler.post(wait);
        }
    }

    /** Full screen: the system bars come back with a swipe from the edge. */
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus) return;
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) {
                bars.hide(WindowInsets.Type.systemBars());
                bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /**
     * Back follows the dashboard's trail instead of replaying every page seen:
     * the page this one was opened from, then the home page, then the
     * management screen.
     */
    @Override public void onBackPressed() {
        if (!loaded) { super.onBackPressed(); return; }
        // The dashboard keeps the trail of pages (window.moliBack); a page
        // without it falls back to the route one level up.
        web.evaluateJavascript("window.moliBack ? String(window.moliBack()) : 'none'", result -> {
            if ("\"true\"".equals(result)) return;
            if ("\"false\"".equals(result)) { finish(); return; }
            web.evaluateJavascript("location.hash", value -> {
                String parent = parentRoute(value);
                if (parent == null) finish();
                else web.evaluateJavascript("location.hash = " + org.json.JSONObject.quote(parent), null);
            });
        });
    }

    /** The route one level up from a `location.hash` (as JSON), or null at home. */
    static String parentRoute(String hashJson) {
        String hash;
        try {
            Object value = new org.json.JSONTokener(hashJson).nextValue();
            hash = value instanceof String ? (String) value : "";
        } catch (org.json.JSONException invalid) {
            hash = "";
        }
        String route = hash.startsWith("#") ? hash.substring(1) : hash;
        while (route.startsWith("/")) route = route.substring(1);
        while (route.endsWith("/")) route = route.substring(0, route.length() - 1);
        if (route.isEmpty()) return null;
        int cut = route.lastIndexOf('/');
        return cut < 0 ? "#/" : "#/" + route.substring(0, cut);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (loaded) web.saveState(out);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(wait);
        probe.shutdownNow();
        web.destroy();
        super.onDestroy();
    }
}
