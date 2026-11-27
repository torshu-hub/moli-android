package io.moli.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
