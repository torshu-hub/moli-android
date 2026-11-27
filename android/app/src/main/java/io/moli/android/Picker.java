package io.moli.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Choosing devices (or points) of the house, never typing their ids: a list
 * grouped by room, each with its name and what it is, a search, the likely
 * ones first (« Montrer tous les appareils » for the rest), several at once.
 */
final class Picker {
    /** One choice: its id, what the family reads, its room (null: none), whether it fits. */
    static final class Item {
        final String id, title, detail, room;
        final boolean likely;

        Item(String id, String title, String detail, String room, boolean likely) {
            this.id = id;
            this.title = title;
            this.detail = detail;
            this.room = room;
            this.likely = likely;
        }
    }

    private Picker() {}

    static String fold(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Shows `items`, `chosen` ticked; `done` gets the ticked ids, in the
     * list's order, when « OK » is touched.
     */
    static void show(Activity a, String title, List<Item> items, Set<String> chosen, Consumer<List<String>> done) {
        Look ui = new Look(a);
        Set<String> ticked = new LinkedHashSet<>(chosen);
        boolean someUnlikely = false;
        for (Item i : items) someUnlikely |= !i.likely;
        boolean[] all = {!someUnlikely};
        // A chosen one that does not fit is shown anyway.
        for (Item i : items) if (!i.likely && ticked.contains(i.id)) all[0] = true;

        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
        EditText search = ui.field(a.getString(R.string.picker_search), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(search, ui.matchWrap(0, 0, 0, ui.dp(8)));
        Switch every = new Switch(a);
        every.setText(R.string.picker_all);
        every.setTextColor(Look.MUTED);
        every.setChecked(all[0]);
        if (someUnlikely) box.addView(every, ui.matchWrap(0, 0, 0, ui.dp(4)));
        LinearLayout list = ui.column();
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(380)));

        Runnable fill = () -> {
            list.removeAllViews();
            String q = fold(search.getText().toString().trim());
            Map<String, List<Item>> byRoom = new LinkedHashMap<>();
            for (Item i : items) {
                if (!all[0] && !i.likely) continue;
                if (!q.isEmpty() && !fold(i.title + " " + i.detail + " " + i.room).contains(q)) continue;
                byRoom.computeIfAbsent(i.room == null ? "" : i.room, k -> new ArrayList<>()).add(i);
            }
            List<String> rooms = new ArrayList<>(byRoom.keySet());
            rooms.sort((x, y) -> x.isEmpty() ? 1 : y.isEmpty() ? -1 : fold(x).compareTo(fold(y)));
            for (String room : rooms) {
                TextView head = ui.text(room.isEmpty() ? a.getString(R.string.picker_no_room) : room, 13, Look.MUTED, true);
                head.setPadding(0, ui.dp(12), 0, ui.dp(2));
                list.addView(head);
                for (Item i : byRoom.get(room)) {
                    CheckBox c = new CheckBox(a);
                    c.setText(i.detail == null || i.detail.isEmpty() ? i.title : i.title + "  ·  " + i.detail);
                    c.setTextSize(15);
                    c.setTextColor(Look.INK);
                    c.setChecked(ticked.contains(i.id));
                    c.setMinHeight(ui.dp(44));
                    c.setOnCheckedChangeListener((b, on) -> { if (on) ticked.add(i.id); else ticked.remove(i.id); });
                    list.addView(c);
                }
            }
            if (list.getChildCount() == 0) list.addView(ui.note(a.getString(R.string.picker_none)));
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { fill.run(); }
        });
        every.setOnCheckedChangeListener((b, on) -> { all[0] = on; fill.run(); });
        fill.run();

        new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<String> out = new ArrayList<>();
                    Set<String> listed = new java.util.HashSet<>();
                    for (Item i : items) { listed.add(i.id); if (ticked.contains(i.id)) out.add(i.id); }
                    // Chosen before and gone from the house now: kept, not lost by a look.
                    for (String id : ticked) if (!listed.contains(id)) out.add(id);
                    done.accept(out);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
