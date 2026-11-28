package io.moli.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * A screen of the house's settings (android/REFONTE.md, « Un écran de
 * réglages »): its header (back, title, what it is for, « Voir ce qui sera
 * écrit »), its cards in two columns on the tablet, and nothing written
 * before « Enregistrer »: a bar that only shows with changes, one write,
 * Back that asks what to do with them. A screen says what it reads, how it
 * lays out its draft, the draft itself, and how to see and write it.
 */
abstract class SettingsScreen extends Activity {
    protected final Look ui = new Look(this);
    protected final Handler handler = new Handler(Looper.getMainLooper());
    protected final ExecutorService work = Executors.newSingleThreadExecutor();

    /** The draft as written: the screen has changes when its draft differs. */
    private String baseline;
    private boolean busy, ready;
    private LinearLayout root, bar;
    private TextView banner;
    private Button save;
    private ScrollView scroll;

    /** The screen's title and the sentence that says what it is for. */
    protected abstract int title();
    protected abstract int about();

    /** Reads what is written, and the house if Moli answers. Off the UI thread. */
    protected abstract void read() throws Exception;

    /** Lays the draft out from what was read (again after « Annuler » and after a save). */
    protected abstract void fill(LinearLayout first, LinearLayout second);

    /** The draft as it would be written, comparable; null while incomplete. */
    protected abstract String draft();

    /** « Voir ce qui sera écrit »: `{before, after, error}`. Off the UI thread. */
    protected abstract JSONObject preview(String draft) throws Exception;

    /** Writes the draft: null when done, else why not (nothing written). Off the UI thread. */
    protected abstract String write(String draft) throws Exception;

    /** What the screen says once saved. */
    protected int saved() { return R.string.settings_saved; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean house = Setup.config(this).exists() && ProtectedKey.exists(this);
        frame();
        showNote(getString(house ? R.string.settings_reading : R.string.setup_no_house));
        if (house) load();
    }

    /** Turned: the same draft, laid out for the new width. */
    @Override public void onConfigurationChanged(Configuration changed) {
        super.onConfigurationChanged(changed);
        if (!ready) return;
        frame();
        layOut();
    }

    private void load() {
        work.execute(() -> {
            String failure = null;
            try { read(); } catch (Exception unreadable) { failure = String.valueOf(unreadable.getMessage()); }
            String why = failure;
            handler.post(() -> {
                if (isDestroyed()) return;
                if (why != null) { showNote(getString(R.string.setup_failed, why)); return; }
                ready = true;
                layOut();
                baseline = draft();
                changed();
            });
        });
    }

    // ---- The frame: header, banner, page, bar ----

    private void frame() {
        boolean wide = wide();
        root = ui.column();
        int side = ui.dp(wide ? 28 : 16);
        root.setPadding(side, ui.dp(wide ? 20 : 12), side, 0);

        LinearLayout head = ui.row();
        android.widget.ImageView back = ui.icon(R.drawable.ic_arrow_back, Look.INK, 26);
        back.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        back.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        ui.touchable(back, ui.round(Look.PAGE, 20));
        back.setContentDescription(getString(R.string.settings_back));
        back.setOnClickListener(v -> onBackPressed());
        head.addView(back);
        LinearLayout titles = ui.column();
        titles.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        titles.addView(ui.text(getString(title()), 24, Look.INK, true));
        TextView about = ui.text(getString(about()), 14, Look.MUTED, false);
        titles.addView(about);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button preview = ui.flat(getString(R.string.settings_preview));
        preview.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_code, Look.ACCENT), null, null, null);
        preview.setCompoundDrawablePadding(ui.dp(6));
        preview.setPadding(ui.dp(12), 0, ui.dp(14), 0);
        preview.setOnClickListener(v -> showPreview());
        if (!wide) {
            preview.setText("");
            preview.setContentDescription(getString(R.string.settings_preview));
        }
        head.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)));
        root.addView(head, ui.matchWrap(0, 0, 0, ui.dp(12)));

        banner = ui.text("", 15, Color.WHITE, false);
        banner.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        banner.setVisibility(View.GONE);
        root.addView(banner, ui.matchWrap(0, 0, 0, ui.dp(10)));

        scroll = ui.scroll(ui.column());
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        bar = ui.row();
        bar.setPadding(ui.dp(16), ui.dp(10), ui.dp(10), ui.dp(10));
        bar.setBackground(ui.round(Look.INK, 16));
        bar.setElevation(ui.dp(6));
        TextView unsaved = ui.text(getString(R.string.settings_unsaved), 15, Color.WHITE, true);
        bar.addView(unsaved, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button undo = new Button(this);
        undo.setText(R.string.settings_undo);
        undo.setAllCaps(false);
        undo.setTextColor(Color.WHITE);
        undo.setBackgroundColor(Color.TRANSPARENT);
        undo.setOnClickListener(v -> undo());
        bar.addView(undo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)));
        save = new Button(this);
        save.setText(R.string.settings_save);
        save.setAllCaps(false);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setTextColor(Look.INK);
        save.setBackground(ui.round(Color.WHITE, 10));
        save.setPadding(ui.dp(18), 0, ui.dp(18), 0);
        save.setOnClickListener(v -> saveAll(null));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44));
        sp.setMargins(ui.dp(6), 0, 0, 0);
        bar.addView(save, sp);
        bar.setVisibility(View.GONE);
        root.addView(bar, ui.matchWrap(0, ui.dp(4), 0, ui.dp(12)));

        ui.edgeToEdge(root);
        setContentView(root);
    }

    protected boolean wide() { return getResources().getConfiguration().screenWidthDp >= 720; }

    protected android.graphics.drawable.Drawable tinted(int res, int color) {
        android.graphics.drawable.Drawable d = getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    private void showNote(String words) {
        LinearLayout page = ui.column();
        page.addView(ui.note(words));
        scroll.removeAllViews();
        scroll.addView(page);
    }

    /** The cards, side by side on the tablet (3:2), one under the other on a phone. */
    private void layOut() {
        int y = scroll.getScrollY();
        LinearLayout first = ui.column(), second = ui.column();
        fill(first, second);
        LinearLayout panes = new LinearLayout(this);
        if (wide()) {
            panes.setOrientation(LinearLayout.HORIZONTAL);
            panes.addView(first, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3));
            panes.addView(new View(this), new LinearLayout.LayoutParams(ui.dp(16), 1));
            panes.addView(second, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        } else {
            panes.setOrientation(LinearLayout.VERTICAL);
            panes.addView(first);
            panes.addView(second);
        }
        scroll.removeAllViews();
        scroll.addView(panes);
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ---- Changes ----

    /** Any change of the draft: the bar shows while it differs from what is written. */
    protected void changed() {
        if (!ready || bar == null) return;
        String now = draft();
        boolean dirty = now == null || !now.equals(baseline);
        bar.setVisibility(dirty ? View.VISIBLE : View.GONE);
        save.setEnabled(now != null && !busy);
        save.setAlpha(now != null ? 1f : 0.5f);
    }

    /** The page laid out again from the draft (a list grew, a card changed shape). */
    protected void relayout() {
        if (ready) layOut();
        changed();
    }

    /** « Annuler »: the page as written. */
    private void undo() {
        if (busy) return;
        ready = false;
        work.execute(() -> {
            String failure = null;
            try { read(); } catch (Exception unreadable) { failure = String.valueOf(unreadable.getMessage()); }
            String why = failure;
            handler.post(() -> {
                if (isDestroyed()) return;
                ready = true;
                if (why != null) { banner(getString(R.string.setup_failed, why), Look.BAD); return; }
                layOut();
                baseline = draft();
                changed();
            });
        });
    }

    protected void banner(String text, int color) {
        banner.setText(text);
        banner.setBackground(ui.round(color, 10));
        banner.setVisibility(View.VISIBLE);
    }

    // ---- Seeing, saving ----

    private void showPreview() {
        if (!ready) return;
        String draft = draft();
        if (draft == null) { banner(getString(R.string.settings_incomplete), Look.WARN); return; }
        work.execute(() -> {
            JSONObject seen;
            try { seen = preview(draft); } catch (Exception failure) { seen = null; }
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
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.settings_preview)
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

    /** One write, one check; the page stays as it is. `after`: once saved. */
    private void saveAll(Runnable after) {
        if (busy || !ready) return;
        String draft = draft();
        if (draft == null) { banner(getString(R.string.settings_incomplete), Look.WARN); return; }
        busy = true;
        save.setEnabled(false);
        banner(getString(R.string.settings_saving), Look.INFO);
        work.execute(() -> {
            String refused;
            try { refused = write(draft); } catch (Exception failure) { refused = String.valueOf(failure.getMessage()); }
            String why = refused;
            handler.post(() -> {
                busy = false;
                if (isDestroyed()) return;
                if (why == null) {
                    // What was just written is the page now (a typed key no longer shown).
                    layOut();
                    baseline = draft();
                    changed();
                    banner(getString(saved()), Look.OK);
                    handler.postDelayed(() -> { if (!busy) banner.setVisibility(View.GONE); }, 5000);
                    if (after != null) after.run();
                } else {
                    banner.setVisibility(View.GONE);
                    changed();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.setup_refused)
                            .setMessage(getString(R.string.settings_refused_kept, why))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            });
        });
    }

    @Override public void onBackPressed() {
        if (busy) return;
        String now = ready ? draft() : baseline;
        if (!ready || now != null && now.equals(baseline)) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_unsaved)
                .setMessage(R.string.settings_unsaved_question)
                .setPositiveButton(R.string.settings_save, (d, w) -> saveAll(this::finish))
                .setNegativeButton(R.string.energy_discard, (d, w) -> finish())
                .setNeutralButton(R.string.settings_stay, null)
                .show();
    }

    @Override protected void onDestroy() { work.shutdown(); super.onDestroy(); }

    // ---- Pieces every screen uses ----

    /** A card with its title and, under it, what it is for. */
    protected LinearLayout section(int title, int about) {
        LinearLayout card = ui.card();
        card.addView(ui.text(getString(title), 18, Look.INK, true));
        if (about != 0) card.addView(ui.note(getString(about)));
        return card;
    }

    /** An amber line under what it is about: a setting nobody will see the effect of. */
    protected TextView warning(String words) {
        TextView t = ui.text(words, 13, Look.WARN, false);
        t.setPadding(0, ui.dp(2), 0, ui.dp(4));
        return t;
    }

    /** A small round button with an icon (up, down, remove). */
    protected View iconButton(int res, int tint, int description, Runnable action) {
        android.widget.ImageView b = ui.icon(res, tint, 22);
        b.setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9));
        b.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        ui.touchable(b, ui.round(Look.CARD, 20));
        b.setContentDescription(getString(description));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /**
     * « − 19,5 °C + »: a number chosen by steps, never typed, within
     * [min, max]; `fallback` is what Moli takes when the file says nothing.
     */
    protected View stepper(String label, Double value, double fallback, double step, double min, double max, String unit,
                           java.util.function.Consumer<Double> set) {
        LinearLayout line = ui.row();
        line.setPadding(0, ui.dp(4), 0, ui.dp(4));
        TextView words = ui.text(label, 15, Look.INK, false);
        line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        double[] now = {value == null ? fallback : value};
        TextView shown = ui.text("", 16, Look.INK, true);
        shown.setGravity(Gravity.CENTER);
        Runnable show = () -> shown.setText(new java.text.DecimalFormat(step < 1 ? "0.0" : "0").format(now[0]) + (unit.isEmpty() ? "" : " " + unit));
        show.run();
        line.addView(iconButton(R.drawable.ic_remove, Look.ACCENT, R.string.settings_less, () -> {
            if (now[0] - step < min - 1e-9) return;
            now[0] = Math.round((now[0] - step) / step) * step;
            show.run();
            set.accept(now[0]);
        }));
        line.addView(shown, new LinearLayout.LayoutParams(ui.dp(88), ViewGroup.LayoutParams.WRAP_CONTENT));
        line.addView(iconButton(R.drawable.ic_add, Look.ACCENT, R.string.settings_more, () -> {
            if (now[0] + step > max + 1e-9) return;
            now[0] = Math.round((now[0] + step) / step) * step;
            show.run();
            set.accept(now[0]);
        }));
        return line;
    }

    /** A line that says what is chosen, and a button to choose it. */
    protected View chosen(String label, String value, String warning, Runnable choose) {
        LinearLayout box = ui.column();
        box.setPadding(0, ui.dp(6), 0, ui.dp(6));
        LinearLayout line = ui.row();
        LinearLayout words = ui.column();
        words.addView(ui.text(label, 13, Look.MUTED, true));
        words.addView(ui.text(value == null || value.isEmpty() ? getString(R.string.settings_none_chosen) : value, 15,
                value == null || value.isEmpty() ? Look.MUTED : Look.INK, false));
        line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button b = ui.flat(getString(R.string.home_choose));
        b.setOnClickListener(v -> choose.run());
        line.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        box.addView(line);
        if (warning != null) box.addView(warning(warning));
        return box;
    }

    /** A slug for a new id, from its name: lower case, no accents, dashes; never one of `taken`. */
    static String slug(String name, java.util.Collection<String> taken, String fallback) {
        String base = Picker.fold(name).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = fallback;
        if (base.length() > 40) base = base.substring(0, 40);
        String id = base;
        for (int n = 2; taken.contains(id); n++) id = base + "-" + n;
        return id;
    }

    /** A text link with a leading icon: « Ajouter… ». */
    protected Button addButton(int label, Runnable action) {
        Button b = ui.flat(getString(label));
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_add, Look.ACCENT), null, null, null);
        b.setCompoundDrawablePadding(ui.dp(6));
        b.setGravity(Gravity.CENTER);
        b.setPadding(ui.dp(12), 0, ui.dp(16), 0);
        b.setOnClickListener(v -> action.run());
        return b;
    }
}
