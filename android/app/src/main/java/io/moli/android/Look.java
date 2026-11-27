package io.moli.android;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The look of the house's settings screens (colours, cards, fields,
 * buttons), the same as the integrations screen's: one place for the
 * screens that came after it.
 */
final class Look {
    static final int INK = 0xFF1B2733, MUTED = 0xFF5C6B7A, PAGE = 0xFFF2F4F7,
            CARD = Color.WHITE, ACCENT = 0xFF234E70, LINE = 0xFFDDE3EA,
            OK = 0xFF2E7D4F, WARN = 0xFFB26A00, BAD = 0xFFB3261E, INFO = 0xFF2F5FA7;

    /** The hues of the home screen's tiles: the icon's colour on its soft ground. */
    static final int[] BLUE = {0xFF2F5FA7, 0xFFE3ECF8}, TEAL = {0xFF1F7A73, 0xFFDCF1EE},
            AMBER = {0xFFA65F00, 0xFFFDEFD6}, SLATE = {0xFF45596D, 0xFFE6EBF1};

    private final Activity a;

    Look(Activity activity) { a = activity; }

    int dp(float v) { return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f); }

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
        TextView t = new TextView(a);
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

    TextView title(String value) {
        TextView t = text(value, 16, INK, true);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    TextView note(String value) {
        TextView t = text(value, 13, MUTED, false);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(a);
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
        ScrollView s = new ScrollView(a);
        s.setClipToPadding(false);
        s.setPadding(0, 0, 0, dp(24));
        s.addView(content);
        return s;
    }

    EditText field(String hint, int type) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextSize(16);
        e.setTextColor(INK);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(stroke(CARD, LINE, 10));
        return e;
    }

    Button primary(String label) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(round(ACCENT, 10));
        return b;
    }

    Button flat(String label) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(ACCENT);
        b.setBackground(stroke(PAGE, ACCENT, 10));
        return b;
    }

    Button danger(String label) {
        Button b = flat(label);
        b.setTextColor(BAD);
        b.setBackground(stroke(PAGE, BAD, 10));
        return b;
    }

    /** A Material Symbols icon of res/drawable, in `tint`, `size` dp. */
    ImageView icon(int res, int tint, int size) {
        ImageView i = new ImageView(a);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(tint));
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return i;
    }

    /** An icon on a soft rounded square: what a tile is about, at a glance. */
    View badge(int res, int[] hue) {
        LinearLayout b = row();
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(hue[1], 12));
        b.addView(icon(res, hue[0], 24));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return b;
    }

    /** A view that answers a touch with the system's ripple, on `shape`. */
    View touchable(View v, GradientDrawable shape) {
        GradientDrawable mask = round(Color.BLACK, 14);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1F234E70), shape, mask));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    /**
     * A tile of the home screen: its badge, its title and one line that says
     * where things stand (`summary`, filled later), the whole tile a button.
     */
    LinearLayout tile(int res, int[] hue, String title, TextView summary, Runnable action) {
        LinearLayout t = row();
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), dp(16), dp(12), dp(16));
        t.setMinimumHeight(dp(84));
        t.setElevation(dp(1));
        touchable(t, round(CARD, 14));
        t.addView(badge(res, hue));
        LinearLayout words = column();
        words.setPadding(dp(14), 0, dp(8), 0);
        words.addView(text(title, 17, INK, true));
        summary.setTextSize(13);
        summary.setTextColor(MUTED);
        summary.setMaxLines(2);
        words.addView(summary);
        t.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (action != null) {
            t.addView(icon(R.drawable.ic_chevron_right, LINE, 22));
            t.setOnClickListener(v -> action.run());
        }
        return t;
    }

    /** A pill to choose with: filled when chosen. */
    TextView chip(String value, boolean chosen, Runnable action) {
        TextView c = text(value, 14, chosen ? Color.WHITE : INK, chosen);
        c.setPadding(dp(14), dp(8), dp(14), dp(8));
        c.setGravity(Gravity.CENTER);
        touchable(c, chosen ? round(ACCENT, 18) : stroke(CARD, LINE, 18));
        c.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, dp(8), dp(8));
        c.setLayoutParams(p);
        return c;
    }

    /** Pills side by side, scrolled sideways when they do not fit. */
    View chips(java.util.List<View> pills) {
        LinearLayout line = row();
        for (View p : pills) line.addView(p);
        android.widget.HorizontalScrollView s = new android.widget.HorizontalScrollView(a);
        s.setHorizontalScrollBarEnabled(false);
        s.addView(line);
        return s;
    }

    /**
     * A screen's header, as the settings screens have it: back, its title,
     * the sentence that says what it is for, then `actions` on the right.
     */
    LinearLayout header(String title, String about, View... actions) {
        LinearLayout head = row();
        ImageView back = icon(R.drawable.ic_arrow_back, INK, 26);
        back.setPadding(dp(4), dp(4), dp(4), dp(4));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        touchable(back, round(PAGE, 20));
        back.setContentDescription(a.getString(R.string.settings_back));
        back.setOnClickListener(v -> a.onBackPressed());
        head.addView(back);
        LinearLayout titles = column();
        titles.setPadding(dp(10), 0, dp(10), 0);
        titles.addView(text(title, 24, INK, true));
        if (about != null) titles.addView(text(about, 14, MUTED, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        for (View action : actions) head.addView(action);
        head.setLayoutParams(matchWrap(0, 0, 0, dp(12)));
        return head;
    }

    /** The title of a group of tiles. */
    TextView group(String value) {
        TextView t = text(value, 14, MUTED, true);
        t.setLetterSpacing(0.04f);
        t.setPadding(dp(4), dp(22), 0, dp(10));
        return t;
    }

    /**
     * Tiles in `columns` columns, rows of equal height; an incomplete last
     * row keeps its tiles the width of the others.
     */
    LinearLayout grid(java.util.List<View> tiles, int columns) {
        LinearLayout g = column();
        for (int i = 0; i < tiles.size(); i += columns) {
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < columns; c++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (c > 0) p.setMargins(dp(12), 0, 0, 0);
                View cell = i + c < tiles.size() ? tiles.get(i + c) : new View(a);
                line.addView(cell, p);
            }
            g.addView(line, matchWrap(0, 0, 0, dp(12)));
        }
        return g;
    }

    /** How many tiles side by side: three on a tablet, two on a wide phone, else one. */
    int columns() {
        int width = a.getResources().getConfiguration().screenWidthDp;
        return width >= 720 ? 3 : width >= 480 ? 2 : 1;
    }

    /**
     * The page drawn under the system bars (as Android 15 does by itself),
     * `root` kept clear of them by its padding; dark icons on the light page.
     */
    void edgeToEdge(View root) {
        Window w = a.getWindow();
        w.getDecorView().setBackgroundColor(PAGE);
        int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController bars = w.getInsetsController();
            if (bars != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                bars.setSystemBarsAppearance(light, light);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (Build.VERSION.SDK_INT < 35) {
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(PAGE);
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(l + insets.getSystemWindowInsetLeft(), t + insets.getSystemWindowInsetTop(),
                    r + insets.getSystemWindowInsetRight(), b + insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    LinearLayout.LayoutParams matchWrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    LinearLayout.LayoutParams share(int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.setMargins(left, 0, 0, 0);
        return p;
    }
}
