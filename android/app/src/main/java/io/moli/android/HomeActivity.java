package io.moli.android;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * « Pièces et affichage »: the house's layout, `data/home.json`
 * (docs/components/dashboard.md): its rooms (order, icon, floor, outside,
 * other names), what the dashboard's home page shows first, what it never
 * shows or never makes a button, and its look. Written without restarting
 * Moli; the keys this screen does not set stay as they are (home.rs).
 */
public final class HomeActivity extends SettingsScreen {
    /** The room icons of the dashboard (web/src/maison/lib/icons.js), and this app's drawing of each. */
    static final String[] ICONS = {"home", "sofa-outline", "silverware-fork-knife", "bed-outline", "bed-king-outline",
            "shower-head", "toilet", "desk-lamp", "atelier", "garage", "stairs", "door", "tv", "pool",
            "flower-outline", "sprout", "home-roof", "car", "dog", "leaf"};
    static final int[] DRAWN = {R.drawable.ic_home, R.drawable.ic_weekend, R.drawable.ic_restaurant, R.drawable.ic_bed,
            R.drawable.ic_king_bed, R.drawable.ic_shower, R.drawable.ic_wc, R.drawable.ic_desk, R.drawable.ic_handyman,
            R.drawable.ic_garage, R.drawable.ic_stairs, R.drawable.ic_door_front, R.drawable.ic_tv, R.drawable.ic_pool,
            R.drawable.ic_local_florist, R.drawable.ic_potted_plant, R.drawable.ic_roofing, R.drawable.ic_directions_car,
            R.drawable.ic_pets, R.drawable.ic_eco};
    /** The icon a room's name calls for (the family can choose another). */
    static final String[][] GUESS = {{"salon|sejour|living", "sofa-outline"}, {"cuisine|salle a manger|kitchen|dining", "silverware-fork-knife"},
            {"parent|suite", "bed-king-outline"}, {"chambre|bedroom|dortoir", "bed-outline"}, {"bain|douche|bath|shower", "shower-head"},
            {"toilette|wc", "toilet"}, {"bureau|office", "desk-lamp"}, {"atelier|buanderie|cellier|workshop|laundry", "atelier"},
            {"garage", "garage"}, {"couloir|escalier|palier|hall|stairs", "stairs"}, {"entree|porche|entrance", "door"},
            {"cinema|tele|media", "tv"}, {"piscine|spa|pool", "pool"}, {"jardin|terrasse|garden|patio", "flower-outline"},
            {"potager|serre", "sprout"}, {"grenier|combles|attic|toit", "home-roof"}, {"voiture|carport|parking", "car"}};
    static final String[] THEMES = {"porcelaine", "sauge", "ocean", "terracotta"};
    static final int[] THEME_WORDS = {R.string.home_theme_porcelaine, R.string.home_theme_sauge, R.string.home_theme_ocean, R.string.home_theme_terracotta};
    static final String[] POSITIONS = {"left", "right", "top", "bottom"};
    static final int[] POSITION_WORDS = {R.string.home_menu_left, R.string.home_menu_right, R.string.home_menu_top, R.string.home_menu_bottom};
    static final String[] SHAPES = {"classic", "compact", "floating"};
    static final int[] SHAPE_WORDS = {R.string.home_menu_classic, R.string.home_menu_compact, R.string.home_menu_floating};

    /** The favourites' lists of the home page: key in `favorites`, the kind of device that fits, its title. */
    static final String[] FAVORITES = {"lights", "switches", "climate", "cameras"};
    static final String[] FITS = {"light", "switch", "climate", "camera"};
    static final int[] FAVORITE_WORDS = {R.string.home_fav_lights, R.string.home_fav_switches, R.string.home_fav_climate, R.string.home_fav_cameras};

    // ---- What is written, what Moli runs ----

    /** home.json as read. */
    private JSONObject written = new JSONObject();
    /** The house's devices (null: Moli did not answer) and rooms as /api/home decides them. */
    private JSONArray devices;
    private final Map<String, JSONObject> deviceById = new HashMap<>();
    private final Map<String, Boolean> outsideSeen = new HashMap<>();

    // ---- The draft ----

    private String title;
    private List<JSONObject> rooms = new ArrayList<>();
    private List<String> hidden = new ArrayList<>(), quiet = new ArrayList<>();
    private final Map<String, List<JSONObject>> favorites = new LinkedHashMap<>();
    private List<JSONObject> doors = new ArrayList<>();
    private String theme, menuPosition, menuShape;

    @Override protected int title() { return R.string.home_screen; }
    @Override protected int about() { return R.string.home_screen_about; }

    // ---- Reading ----

    @Override protected void read() throws Exception {
        JSONObject layout = Setup.home(this).optJSONObject("layout");
        JSONObject house = null;
        try { house = Setup.homeHouse(this); } catch (Exception silent) { /* Moli not answering: what is written only */ }
        written = layout == null ? new JSONObject() : layout;
        devices = house == null ? null : house.optJSONArray("devices");
        deviceById.clear();
        outsideSeen.clear();
        for (int i = 0; devices != null && i < devices.length(); i++)
            deviceById.put(devices.optJSONObject(i).optString("id"), devices.optJSONObject(i));
        JSONArray seen = house == null ? null : house.optJSONArray("rooms");
        for (int i = 0; seen != null && i < seen.length(); i++)
            outsideSeen.put(Picker.fold(seen.optJSONObject(i).optString("name")), seen.optJSONObject(i).optBoolean("outside"));
        handler.post(this::take);
    }

    /** The draft from what is written. */
    private void take() {
        title = written.optString("title", "");
        rooms = objects(written.optJSONArray("rooms"));
        hidden = strings(written.optJSONArray("hidden"));
        quiet = strings(written.optJSONArray("quiet"));
        JSONObject fav = written.optJSONObject("favorites");
        favorites.clear();
        for (String key : FAVORITES) favorites.put(key, objects(fav == null ? null : fav.optJSONArray(key)));
        doors = objects(written.optJSONArray("doors"));
        theme = written.optString("theme", null);
        JSONObject menu = written.optJSONObject("menu");
        menuPosition = menu == null ? null : menu.optString("position", null);
        menuShape = menu == null ? null : menu.optString("shape", null);
    }

    static List<JSONObject> objects(JSONArray a) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) if (a.optJSONObject(i) != null) out.add(EnergyActivity.copy(a.optJSONObject(i)));
        return out;
    }

    static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) if (!a.optString(i).isEmpty()) out.add(a.optString(i));
        return out;
    }

    // ---- The draft as written ----

    /** The keys this screen sets, as they would be written (absent: not in the file). */
    private JSONObject managed() throws JSONException {
        JSONObject out = new JSONObject();
        if (!title.trim().isEmpty()) out.put("title", title.trim());
        if (!rooms.isEmpty()) out.put("rooms", new JSONArray(rooms));
        if (!hidden.isEmpty()) out.put("hidden", new JSONArray(hidden));
        if (!quiet.isEmpty()) out.put("quiet", new JSONArray(quiet));
        JSONObject fav = EnergyActivity.copy(written.optJSONObject("favorites"));
        for (String key : FAVORITES) {
            if (favorites.get(key).isEmpty()) fav.remove(key);
            else fav.put(key, new JSONArray(favorites.get(key)));
        }
        if (fav.length() > 0) out.put("favorites", fav);
        if (!doors.isEmpty()) out.put("doors", new JSONArray(doors));
        if (theme != null) out.put("theme", theme);
        JSONObject menu = EnergyActivity.copy(written.optJSONObject("menu"));
        if (menuPosition == null) menu.remove("position"); else menu.put("position", menuPosition);
        if (menuShape == null) menu.remove("shape"); else menu.put("shape", menuShape);
        if (menu.length() > 0) out.put("menu", menu);
        return out;
    }

    static final String[] KEYS = {"title", "rooms", "hidden", "quiet", "favorites", "doors", "theme", "menu"};

    /** What changes in the file: each key set whole, JSON null for a key taken out. */
    private JSONObject set(String draft) throws JSONException {
        JSONObject now = new JSONObject(draft), set = new JSONObject();
        for (String key : KEYS) {
            Object after = now.opt(key), before = written.opt(key);
            if (after == null && before == null) continue;
            if (after != null && before != null && after.toString().equals(before.toString())) continue;
            set.put(key, after == null ? JSONObject.NULL : after);
        }
        return set;
    }

    @Override protected String draft() {
        try {
            for (JSONObject r : rooms) if (r.optString("name").trim().isEmpty()) return null;
            return managed().toString();
        } catch (JSONException impossible) { return null; }
    }

    @Override protected JSONObject preview(String draft) throws Exception { return Setup.homePreview(this, set(draft)); }

    @Override protected String write(String draft) throws Exception {
        JSONObject set = set(draft);
        if (set.length() == 0) return null;
        String refused = Setup.homePut(this, set);
        if (refused == null) {
            JSONObject now = new JSONObject(draft);
            for (String key : KEYS) { if (now.has(key)) written.put(key, now.get(key)); else written.remove(key); }
        }
        return refused;
    }

    @Override protected int saved() { return R.string.home_saved; }

    // ---- The page ----

    @Override protected void fill(LinearLayout first, LinearLayout second) {
        if (devices == null) {
            LinearLayout note = ui.card();
            note.addView(ui.note(getString(R.string.home_no_moli)));
            first.addView(note);
        }
        first.addView(roomsCard());
        second.addView(homePageCard());
        second.addView(discretionCard());
        second.addView(lookCard());
    }

    // ---- Rooms ----

    /** The devices Moli puts in a room (its name or one of its other names). */
    private List<JSONObject> devicesIn(JSONObject room) {
        Set<String> names = new HashSet<>();
        names.add(Picker.fold(room.optString("name")).trim());
        for (String alias : strings(room.optJSONArray("aliases"))) names.add(Picker.fold(alias).trim());
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; devices != null && i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (!d.isNull("room") && names.contains(Picker.fold(d.optString("room")).trim())) out.add(d);
        }
        return out;
    }

    /** The rooms the devices name that the list does not hold yet, with how many devices each. */
    private Map<String, Integer> unlisted() {
        Set<String> known = new HashSet<>();
        for (JSONObject r : rooms) {
            known.add(Picker.fold(r.optString("name")).trim());
            for (String alias : strings(r.optJSONArray("aliases"))) known.add(Picker.fold(alias).trim());
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; devices != null && i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d.isNull("room") || d.optString("room").trim().isEmpty()) continue;
            String room = d.optString("room").trim();
            if (known.contains(Picker.fold(room))) continue;
            String same = null;
            for (String k : out.keySet()) if (Picker.fold(k).equals(Picker.fold(room))) same = k;
            out.merge(same == null ? room : same, 1, Integer::sum);
        }
        return out;
    }

    static String guessIcon(String name) {
        String n = Picker.fold(name);
        for (String[] g : GUESS) if (n.matches(".*\\b(" + g[0] + ").*")) return g[1];
        return "home";
    }

    static int drawn(String icon) {
        for (int i = 0; i < ICONS.length; i++) if (ICONS[i].equals(icon)) return DRAWN[i];
        return R.drawable.ic_home;
    }

    private View roomsCard() {
        LinearLayout card = section(R.string.home_rooms, R.string.home_rooms_about);
        for (int i = 0; i < rooms.size(); i++) card.addView(roomRow(i));
        if (rooms.isEmpty()) card.addView(ui.note(getString(R.string.home_rooms_none)));
        LinearLayout actions = ui.row();
        actions.addView(addButton(R.string.home_room_add, () -> editRoom(-1)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)));
        card.addView(actions, ui.matchWrap(0, ui.dp(8), 0, 0));

        Map<String, Integer> proposed = unlisted();
        if (!proposed.isEmpty()) {
            LinearLayout box = ui.column();
            box.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
            box.setBackground(ui.round(Look.BLUE[1], 12));
            box.addView(ui.text(getString(R.string.home_proposed), 15, Look.BLUE[0], true));
            box.addView(ui.note(getString(R.string.home_proposed_about)));
            for (Map.Entry<String, Integer> e : proposed.entrySet()) {
                LinearLayout line = ui.row();
                line.addView(ui.icon(drawn(guessIcon(e.getKey())), Look.BLUE[0], 22));
                TextView words = ui.text(e.getKey() + "  ·  " + getResources().getQuantityString(R.plurals.home_devices, e.getValue(), e.getValue()), 15, Look.INK, false);
                words.setPadding(ui.dp(10), 0, 0, 0);
                line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button add = ui.flat(getString(R.string.home_proposed_add));
                add.setOnClickListener(v -> { rooms.add(newRoom(e.getKey())); relayout(); });
                line.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
                box.addView(line, ui.matchWrap(0, ui.dp(4), 0, 0));
            }
            if (proposed.size() > 1) {
                Button all = ui.primary(getString(R.string.home_proposed_all));
                all.setOnClickListener(v -> { for (String name : proposed.keySet()) rooms.add(newRoom(name)); relayout(); });
                box.addView(all, ui.matchWrap(0, ui.dp(8), 0, 0));
            }
            card.addView(box, ui.matchWrap(0, ui.dp(12), 0, 0));
        }
        return card;
    }

    private JSONObject newRoom(String name) {
        try {
            return new JSONObject().put("name", name).put("icon", guessIcon(name));
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private View roomRow(int index) {
        JSONObject room = rooms.get(index);
        LinearLayout line = ui.row();
        line.setPadding(0, ui.dp(6), 0, ui.dp(6));
        line.addView(ui.badge(drawn(room.optString("icon", guessIcon(room.optString("name")))), Look.AMBER));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        words.addView(ui.text(room.optString("name"), 16, Look.INK, true));
        List<String> detail = new ArrayList<>();
        if (devices != null) {
            int n = devicesIn(room).size();
            detail.add(n == 0 ? getString(R.string.home_room_empty) : getResources().getQuantityString(R.plurals.home_devices, n, n));
        }
        if (!room.optString("floor").isEmpty()) detail.add(room.optString("floor"));
        Boolean outside = room.has("outside") ? room.optBoolean("outside") : outsideSeen.get(Picker.fold(room.optString("name")));
        if (Boolean.TRUE.equals(outside)) detail.add(getString(R.string.home_room_outside_word));
        List<String> aliases = strings(room.optJSONArray("aliases"));
        if (!aliases.isEmpty()) detail.add(getString(R.string.home_room_aka, String.join(", ", aliases)));
        if (!detail.isEmpty()) words.addView(ui.text(String.join("  ·  ", detail), 13, Look.MUTED, false));
        line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (index > 0) line.addView(iconButton(R.drawable.ic_arrow_upward, Look.MUTED, R.string.home_room_up, () -> { rooms.add(index - 1, rooms.remove(index)); relayout(); }));
        if (index < rooms.size() - 1) line.addView(iconButton(R.drawable.ic_arrow_downward, Look.MUTED, R.string.home_room_down, () -> { rooms.add(index + 1, rooms.remove(index)); relayout(); }));
        ui.touchable(line, ui.round(Look.CARD, 12));
        line.setOnClickListener(v -> editRoom(index));
        return line;
    }

    /** A room's sheet: its name, icon, floor, inside or outside, other names. `index` -1: a new room. */
    private void editRoom(int index) {
        JSONObject room = index < 0 ? newRoom("") : EnergyActivity.copy(rooms.get(index));
        String former = room.optString("name");
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);

        EditText name = ui.field(getString(R.string.home_room_name), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(former);
        box.addView(name);

        box.addView(label(R.string.home_room_icon));
        String[] icon = {room.optString("icon", guessIcon(former))};
        LinearLayout icons = ui.column();
        Runnable drawIcons = new Runnable() {
            @Override public void run() {
                icons.removeAllViews();
                LinearLayout line = null;
                for (int i = 0; i < ICONS.length; i++) {
                    if (i % 7 == 0) { line = ui.row(); icons.addView(line); }
                    boolean on = ICONS[i].equals(icon[0]);
                    LinearLayout cell = ui.row();
                    cell.setGravity(Gravity.CENTER);
                    cell.addView(ui.icon(DRAWN[i], on ? 0xFFFFFFFF : Look.INK, 24));
                    ui.touchable(cell, on ? ui.round(Look.ACCENT, 12) : ui.round(Look.PAGE, 12));
                    String chosen = ICONS[i];
                    Runnable self = this;
                    cell.setOnClickListener(v -> { icon[0] = chosen; self.run(); });
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(42), ui.dp(42));
                    p.setMargins(0, 0, ui.dp(6), ui.dp(6));
                    line.addView(cell, p);
                }
            }
        };
        drawIcons.run();
        box.addView(icons);

        box.addView(label(R.string.home_room_floor));
        EditText floor = ui.field(getString(R.string.home_room_floor_hint), InputType.TYPE_CLASS_TEXT);
        floor.setText(room.optString("floor"));
        box.addView(floor);
        Set<String> floors = new java.util.TreeSet<>();
        for (JSONObject r : rooms) if (!r.optString("floor").isEmpty()) floors.add(r.optString("floor"));
        if (floors.isEmpty()) { floors.add(getString(R.string.home_floor_down)); floors.add(getString(R.string.home_floor_up)); }
        List<View> floorChips = new ArrayList<>();
        for (String f : floors) floorChips.add(ui.chip(f, false, () -> floor.setText(f)));
        box.addView(ui.chips(floorChips), ui.matchWrap(0, ui.dp(8), 0, 0));

        box.addView(label(R.string.home_room_where));
        Boolean seen = outsideSeen.get(Picker.fold(former));
        String[] where = {room.has("outside") ? (room.optBoolean("outside") ? "out" : "in") : "auto"};
        LinearLayout whereBox = ui.column();
        Runnable drawWhere = new Runnable() {
            @Override public void run() {
                whereBox.removeAllViews();
                List<View> c = new ArrayList<>();
                Runnable self = this;
                String auto = seen == null ? getString(R.string.home_where_auto)
                        : getString(R.string.home_where_auto_says, getString(seen ? R.string.home_where_out : R.string.home_where_in));
                c.add(ui.chip(auto, "auto".equals(where[0]), () -> { where[0] = "auto"; self.run(); }));
                c.add(ui.chip(getString(R.string.home_where_in), "in".equals(where[0]), () -> { where[0] = "in"; self.run(); }));
                c.add(ui.chip(getString(R.string.home_where_out), "out".equals(where[0]), () -> { where[0] = "out"; self.run(); }));
                whereBox.addView(ui.chips(c));
            }
        };
        drawWhere.run();
        box.addView(whereBox);
        box.addView(ui.note(getString(R.string.home_where_about)));

        box.addView(label(R.string.home_room_aliases));
        List<String> aliases = strings(room.optJSONArray("aliases"));
        LinearLayout aliasBox = ui.column();
        EditText alias = ui.field(getString(R.string.home_room_alias_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        Runnable drawAliases = new Runnable() {
            @Override public void run() {
                aliasBox.removeAllViews();
                List<View> c = new ArrayList<>();
                Runnable self = this;
                for (String a : aliases) c.add(ui.chip(a + "  ✕", false, () -> { aliases.remove(a); self.run(); }));
                if (!c.isEmpty()) aliasBox.addView(ui.chips(c));
            }
        };
        drawAliases.run();
        box.addView(aliasBox);
        LinearLayout addAlias = ui.row();
        addAlias.addView(alias, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button plus = ui.flat(getString(R.string.home_room_alias_add));
        plus.setOnClickListener(v -> {
            String a = alias.getText().toString().trim();
            if (!a.isEmpty() && !aliases.contains(a)) aliases.add(a);
            alias.setText("");
            drawAliases.run();
        });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44));
        pp.setMargins(ui.dp(8), 0, 0, 0);
        addAlias.addView(plus, pp);
        box.addView(addAlias);
        box.addView(ui.note(getString(R.string.home_room_aliases_about)));

        AlertDialog.Builder sheet = new AlertDialog.Builder(this)
                .setTitle(index < 0 ? R.string.home_room_add : R.string.home_room_edit)
                .setView(ui.scroll(box))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null);
        if (index >= 0) sheet.setNeutralButton(R.string.home_room_remove, (d, w) -> { rooms.remove(index); relayout(); });
        AlertDialog dialog = sheet.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { name.setError(getString(R.string.home_room_name_missing)); return; }
            String typed = alias.getText().toString().trim();
            if (!typed.isEmpty() && !aliases.contains(typed)) aliases.add(typed);
            // Renamed: the former name stays known, so the devices that still say it stay in the room.
            if (!former.isEmpty() && !Picker.fold(former).equals(Picker.fold(n)) && !aliases.contains(former)) aliases.add(former);
            aliases.removeIf(a -> Picker.fold(a).equals(Picker.fold(n)));
            String clash = clash(index, n, aliases);
            if (clash != null) { name.setError(getString(R.string.home_room_clash, clash)); return; }
            try {
                room.put("name", n);
                room.put("icon", icon[0]);
                if (floor.getText().toString().trim().isEmpty()) room.remove("floor"); else room.put("floor", floor.getText().toString().trim());
                if ("auto".equals(where[0])) room.remove("outside"); else room.put("outside", "out".equals(where[0]));
                if (aliases.isEmpty()) room.remove("aliases"); else room.put("aliases", new JSONArray(aliases));
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            if (index < 0) rooms.add(room); else rooms.set(index, room);
            dialog.dismiss();
            relayout();
        }));
        dialog.show();
    }

    /** Another room already called so (by its name or another name), or null. */
    private String clash(int index, String name, List<String> aliases) {
        Set<String> mine = new HashSet<>();
        mine.add(Picker.fold(name));
        for (String a : aliases) mine.add(Picker.fold(a));
        for (int i = 0; i < rooms.size(); i++) {
            if (i == index) continue;
            JSONObject r = rooms.get(i);
            if (mine.contains(Picker.fold(r.optString("name")))) return r.optString("name");
            for (String a : strings(r.optJSONArray("aliases"))) if (mine.contains(Picker.fold(a))) return r.optString("name");
        }
        return null;
    }

    private TextView label(int words) {
        TextView t = ui.text(getString(words), 14, Look.INK, true);
        t.setPadding(0, ui.dp(16), 0, ui.dp(6));
        return t;
    }

    // ---- Lists of devices ----

    private String kindWords(String kind) {
        int id = getResources().getIdentifier("home_kind_" + kind, "string", getPackageName());
        return id == 0 ? "" : getString(id);
    }

    private String nameOf(String id) {
        JSONObject d = deviceById.get(id);
        return d == null ? id : d.optString("name", id);
    }

    /** Every device to choose from, those of `fits` (null: all) first. */
    private List<Picker.Item> deviceItems(String fits) {
        List<Picker.Item> out = new ArrayList<>();
        for (int i = 0; devices != null && i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            out.add(new Picker.Item(d.optString("id"), d.optString("name"), kindWords(d.optString("kind")),
                    d.isNull("room") ? null : d.optString("room"), fits == null || fits.equals(d.optString("kind"))));
        }
        return out;
    }

    /** Every switch of the house, a multi-outlet's by outlet: « Multiprise · Lampe ». */
    private List<Picker.Item> switchItems() {
        List<Picker.Item> out = new ArrayList<>();
        for (int i = 0; devices != null && i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            JSONArray s = d.optJSONArray("switches");
            for (int j = 0; s != null && j < s.length(); j++) {
                JSONObject p = s.optJSONObject(j);
                String title = s.length() > 1 ? d.optString("name") + " · " + p.optString("label") : d.optString("name");
                out.add(new Picker.Item(p.optString("point"), title, kindWords(d.optString("kind")),
                        d.isNull("room") ? null : d.optString("room"), !"light".equals(d.optString("kind"))));
            }
        }
        return out;
    }

    /** The name a new entry of a list of the home page takes. */
    private String entryName(String id, boolean point) {
        if (!point) return nameOf(id);
        for (Picker.Item i : switchItems()) if (i.id.equals(id)) return i.title;
        return id;
    }

    /**
     * A list of entries `{id or point, name, …}`: each with its name (a touch
     * renames it), removable; « Choisir » adds or takes out, the order kept.
     */
    private View entries(String title, List<JSONObject> list, boolean point, List<Picker.Item> items) {
        String idKey = point ? "point" : "id";
        LinearLayout box = ui.column();
        TextView head = ui.text(title, 15, Look.INK, true);
        head.setPadding(0, ui.dp(12), 0, ui.dp(4));
        box.addView(head);
        for (JSONObject e : new ArrayList<>(list)) {
            LinearLayout line = ui.row();
            LinearLayout words = ui.column();
            words.addView(ui.text(e.optString("name", entryName(e.optString(idKey), point)), 15, Look.INK, false));
            String id = e.optString(idKey);
            String device = point && id.contains("/") ? id.substring(0, id.lastIndexOf('/')) : id;
            if (devices != null && !deviceById.containsKey(device)) words.addView(warning(getString(R.string.home_entry_gone)));
            else if (hidden.contains(device)) words.addView(warning(getString(R.string.home_entry_hidden)));
            line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> { list.remove(e); relayout(); }));
            ui.touchable(line, ui.round(Look.CARD, 10));
            line.setOnClickListener(v -> rename(e));
            box.addView(line);
        }
        Button choose = addButton(R.string.home_choose, () -> {
            if (devices == null) { banner(getString(R.string.home_no_moli), Look.WARN); return; }
            Set<String> chosen = new HashSet<>();
            for (JSONObject e : list) chosen.add(e.optString(idKey));
            Picker.show(this, title, items, chosen, picked -> {
                list.removeIf(e -> !picked.contains(e.optString(idKey)));
                for (String id : picked) {
                    if (chosen.contains(id)) continue;
                    try { list.add(new JSONObject().put(idKey, id).put("name", entryName(id, point))); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
                }
                relayout();
            });
        });
        box.addView(choose, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        return box;
    }

    private void rename(JSONObject entry) {
        EditText field = ui.field(getString(R.string.home_entry_name), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setText(entry.optString("name"));
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
        box.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(R.string.home_entry_rename)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String n = field.getText().toString().trim();
                    if (n.isEmpty()) return;
                    try { entry.put("name", n); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
                    relayout();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A plain list of devices (`hidden`, `quiet`), with what each one says. */
    private View deviceList(String title, String about, List<String> list, String fits, String unseen) {
        LinearLayout box = ui.column();
        TextView head = ui.text(title, 15, Look.INK, true);
        head.setPadding(0, ui.dp(12), 0, 0);
        box.addView(head);
        box.addView(ui.note(about));
        for (String id : new ArrayList<>(list)) {
            LinearLayout line = ui.row();
            LinearLayout words = ui.column();
            JSONObject d = deviceById.get(id);
            words.addView(ui.text(nameOf(id) + (d == null || d.isNull("room") ? "" : "  ·  " + d.optString("room")), 15, Look.INK, false));
            if (devices != null && d == null) words.addView(warning(getString(R.string.home_entry_gone)));
            else if (unseen != null && d != null && fits != null && !fits.equals(d.optString("kind")) && !"light".equals(d.optString("kind"))) words.addView(warning(unseen));
            line.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(iconButton(R.drawable.ic_close, Look.MUTED, R.string.home_entry_remove, () -> { list.remove(id); relayout(); }));
            box.addView(line);
        }
        box.addView(addButton(R.string.home_choose, () -> {
            if (devices == null) { banner(getString(R.string.home_no_moli), Look.WARN); return; }
            Picker.show(this, title, deviceItems(fits), new HashSet<>(list), picked -> {
                list.removeIf(id -> !picked.contains(id));
                for (String id : picked) if (!list.contains(id)) list.add(id);
                relayout();
            });
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)));
        return box;
    }

    // ---- Cards of the second column ----

    private View homePageCard() {
        LinearLayout card = section(R.string.home_page, R.string.home_page_about);
        EditText name = ui.field(getString(R.string.home_title_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(title);
        name.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { title = e.toString(); changed(); }
        });
        card.addView(label(R.string.home_title));
        card.addView(name);
        for (int i = 0; i < FAVORITES.length; i++) {
            boolean point = "switches".equals(FAVORITES[i]);
            card.addView(entries(getString(FAVORITE_WORDS[i]), favorites.get(FAVORITES[i]), point,
                    point ? switchItems() : deviceItems(FITS[i])));
        }
        card.addView(entries(getString(R.string.home_doors), doors, false, deviceItems("door")));
        card.addView(ui.note(getString(R.string.home_doors_about)));
        return card;
    }

    private View discretionCard() {
        LinearLayout card = section(R.string.home_discretion, 0);
        card.addView(deviceList(getString(R.string.home_hidden), getString(R.string.home_hidden_about), hidden, null, null));
        card.addView(deviceList(getString(R.string.home_quiet), getString(R.string.home_quiet_about), quiet, "switch",
                getString(R.string.home_quiet_no_button)));
        return card;
    }

    private View lookCard() {
        LinearLayout card = section(R.string.home_look, R.string.home_look_about);
        card.addView(label(R.string.home_theme));
        List<View> themes = new ArrayList<>();
        themes.add(ui.chip(getString(R.string.home_default), theme == null, () -> { theme = null; relayout(); }));
        List<String> ids = new ArrayList<>();
        List<String> words = new ArrayList<>();
        for (int i = 0; i < THEMES.length; i++) { ids.add(THEMES[i]); words.add(getString(THEME_WORDS[i])); }
        JSONArray own = written.optJSONArray("themes");
        for (int i = 0; own != null && i < own.length(); i++) {
            JSONObject t = own.optJSONObject(i);
            if (t == null || t.optString("id").isEmpty() || ids.contains(t.optString("id"))) continue;
            Object n = t.opt("name");
            String shown = n instanceof JSONObject ? ((JSONObject) n).optString("fr", t.optString("id")) : t.optString("name", t.optString("id"));
            ids.add(t.optString("id"));
            words.add(shown);
        }
        if (theme != null && !ids.contains(theme)) { ids.add(theme); words.add(theme); }
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            themes.add(ui.chip(words.get(i), id.equals(theme), () -> { theme = id; relayout(); }));
        }
        card.addView(ui.chips(themes));
        card.addView(label(R.string.home_menu_position));
        card.addView(choices(POSITIONS, POSITION_WORDS, menuPosition, v -> { menuPosition = v; relayout(); }));
        card.addView(label(R.string.home_menu_shape));
        card.addView(choices(SHAPES, SHAPE_WORDS, menuShape, v -> { menuShape = v; relayout(); }));
        return card;
    }

    private View choices(String[] values, int[] words, String chosen, java.util.function.Consumer<String> pick) {
        List<View> c = new ArrayList<>();
        c.add(ui.chip(getString(R.string.home_default), chosen == null, () -> pick.accept(null)));
        for (int i = 0; i < values.length; i++) {
            String v = values[i];
            c.add(ui.chip(getString(words[i]), v.equals(chosen), () -> pick.accept(v)));
        }
        return ui.chips(c);
    }
}
