package io.moli.android;

import android.app.TimePickerDialog;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * « Moli, l'assistant »: `[assistant]` (docs/components/moli.md): the
 * service it talks to and its key (the vault, `assistant/api_key`), its
 * voice, and what it does each day. Several providers with their tiers
 * (`[[assistant.provider]]`) are kept as written.
 */
public final class AssistantActivity extends SettingsScreen {
    static final String OPENAI = "https://api.openai.com/v1", MODEL = "gpt-4.1-mini", TRANSCRIBE = "gpt-4o-mini-transcribe",
            SPEECH = "gpt-4o-mini-tts", VOICE = "coral", RECAP = "21:00";
    static final String[] MODELS = {"gpt-4.1-mini", "gpt-4.1", "gpt-4o-mini"};
    static final String[] VOICES = {"alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse"};

    private House house;
    private JSONObject a = new JSONObject();
    private boolean on, keyStored;
    private String key = "";

    @Override protected int title() { return R.string.assistant_screen; }
    @Override protected int about() { return R.string.assistant_screen_about; }

    @Override protected void read() throws Exception {
        JSONObject sections = Setup.sections(this, "assistant").optJSONObject("sections");
        JSONArray stored = Setup.stored(this, "assistant", new JSONArray().put("api_key"));
        House seen = House.read(this);
        handler.post(() -> {
            house = seen;
            JSONObject written = sections.optJSONObject("assistant");
            on = written != null;
            a = EnergyActivity.copy(written);
            keyStored = stored.length() > 0;
            key = "";
        });
    }

    private String text(String k, String fallback) { return a.has(k) ? a.optString(k) : fallback; }

    /** A key set to its default is left out of the file. */
    private void set(String k, Object value, Object fallback) {
        if (value == null || value.equals(fallback)) a.remove(k); else HeatingActivity.put(a, k, value);
    }

    @Override protected String draft() {
        try {
            JSONObject d = new JSONObject().put("assistant", on ? a : JSONObject.NULL);
            // A typed key is a change to save, never shown in the draft's text.
            if (!key.trim().isEmpty()) d.put("key", key.trim().length());
            return d.toString();
        } catch (JSONException impossible) { return null; }
    }

    @Override protected JSONObject preview(String draft) throws Exception {
        JSONObject d = new JSONObject(draft);
        d.remove("key");
        return Setup.sectionsPreview(this, d);
    }

    @Override protected String write(String draft) throws Exception {
        JSONObject d = new JSONObject(draft);
        if (!key.trim().isEmpty()) {
            Setup.storeSecrets(this, "assistant", new JSONObject().put("api_key", key.trim()));
            keyStored = true;
            handler.post(() -> key = "");
        }
        d.remove("key");
        return Setup.sectionsPut(this, d);
    }

    @Override protected int saved() { return R.string.settings_saved_restarted; }

    // ---- The page ----

    @Override protected void fill(LinearLayout first, LinearLayout second) {
        LinearLayout top = section(R.string.assistant_screen, R.string.assistant_on_about);
        Switch s = new Switch(this);
        s.setText(R.string.assistant_on);
        s.setTextSize(16);
        s.setTextColor(Look.INK);
        s.setChecked(on);
        s.setOnCheckedChangeListener((b, checked) -> { on = checked; relayout(); });
        top.addView(s, ui.matchWrap(0, ui.dp(4), 0, 0));
        first.addView(top);
        if (!on) return;
        first.addView(serviceCard());
        second.addView(voiceCard());
        second.addView(dailyCard());
    }

    private TextView label(int words) {
        TextView t = ui.text(getString(words), 14, Look.INK, true);
        t.setPadding(0, ui.dp(14), 0, ui.dp(6));
        return t;
    }

    private EditText bound(String hint, String value, int type, java.util.function.Consumer<String> set) {
        EditText e = ui.field(hint, type);
        e.setText(value);
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int b, int c, int d) {}
            @Override public void onTextChanged(CharSequence x, int b, int c, int d) {}
            @Override public void afterTextChanged(android.text.Editable ed) { set.accept(ed.toString()); changed(); }
        });
        return e;
    }

    private View serviceCard() {
        LinearLayout card = section(R.string.assistant_service, R.string.assistant_service_about);
        JSONArray providers = a.optJSONArray("provider");
        if (providers != null && providers.length() > 0) card.addView(warning(getString(R.string.assistant_providers_kept, providers.length())));
        boolean openai = OPENAI.equals(text("base_url", OPENAI));
        List<View> chips = new ArrayList<>();
        chips.add(ui.chip("OpenAI", openai, () -> { a.remove("base_url"); relayout(); }));
        chips.add(ui.chip(getString(R.string.assistant_other), !openai, () -> { if (OPENAI.equals(text("base_url", OPENAI))) HeatingActivity.put(a, "base_url", "http://"); relayout(); }));
        card.addView(ui.chips(chips));
        if (!openai) {
            card.addView(bound(getString(R.string.assistant_address), text("base_url", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                    v -> set("base_url", v.trim(), OPENAI)));
            card.addView(ui.note(getString(R.string.assistant_address_about)));
        }
        card.addView(label(R.string.assistant_model));
        EditText model = bound(MODEL, text("model", MODEL), InputType.TYPE_CLASS_TEXT, v -> set("model", v.trim().isEmpty() ? null : v.trim(), MODEL));
        card.addView(model);
        if (openai) {
            List<View> models = new ArrayList<>();
            for (String m : MODELS) models.add(ui.chip(m, m.equals(text("model", MODEL)), () -> { set("model", m, MODEL); relayout(); }));
            card.addView(ui.chips(models), ui.matchWrap(0, ui.dp(8), 0, 0));
        }
        card.addView(label(R.string.assistant_key));
        card.addView(ui.text(getString(keyStored ? R.string.assistant_key_stored : R.string.assistant_key_none), 14, keyStored ? Look.OK : Look.WARN, false));
        EditText k = bound(getString(keyStored ? R.string.assistant_key_replace : R.string.assistant_key_hint), key,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, v -> key = v);
        card.addView(k, ui.matchWrap(0, ui.dp(6), 0, 0));
        card.addView(ui.note(getString(R.string.assistant_key_about)));
        return card;
    }

    private View voiceCard() {
        LinearLayout card = section(R.string.assistant_voice, R.string.assistant_voice_about);
        Switch listen = new Switch(this);
        listen.setText(R.string.assistant_listen_cloud);
        listen.setTextColor(Look.INK);
        listen.setChecked(!"".equals(text("transcribe_model", TRANSCRIBE)));
        listen.setOnCheckedChangeListener((b, c) -> { if (c) a.remove("transcribe_model"); else HeatingActivity.put(a, "transcribe_model", ""); changed(); });
        card.addView(listen);
        Switch speak = new Switch(this);
        speak.setText(R.string.assistant_speak_cloud);
        speak.setTextColor(Look.INK);
        boolean cloud = !"".equals(text("speech_model", SPEECH));
        speak.setChecked(cloud);
        speak.setOnCheckedChangeListener((b, c) -> { if (c) a.remove("speech_model"); else HeatingActivity.put(a, "speech_model", ""); relayout(); });
        card.addView(speak, ui.matchWrap(0, ui.dp(6), 0, 0));
        if (cloud) {
            card.addView(label(R.string.assistant_voice_name));
            List<View> voices = new ArrayList<>();
            for (String v : VOICES) voices.add(ui.chip(v, v.equals(text("speech_voice", VOICE)), () -> { set("speech_voice", v, VOICE); relayout(); }));
            card.addView(ui.chips(voices.subList(0, 6)));
            card.addView(ui.chips(voices.subList(6, voices.size())));
            card.addView(stepper(getString(R.string.assistant_speed), a.has("speech_speed") ? a.optDouble("speech_speed") : null, 1.2, 0.1, 0.5, 2,
                    "×", v -> { set("speech_speed", Math.round(v * 10) / 10.0, 1.2); changed(); }));
            card.addView(label(R.string.assistant_style));
            card.addView(bound(getString(R.string.assistant_style_hint), a.optString("speech_style"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    v -> set("speech_style", v.trim().isEmpty() ? null : v.trim(), null)));
        }
        card.addView(label(R.string.assistant_local));
        card.addView(ui.note(getString(R.string.assistant_local_about)));
        card.addView(bound(getString(R.string.assistant_local_speech), text("local_speech", "127.0.0.1:10200"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                v -> set("local_speech", v.trim(), "127.0.0.1:10200")), ui.matchWrap(0, 0, 0, ui.dp(8)));
        card.addView(bound(getString(R.string.assistant_local_listen), text("local_listen", "127.0.0.1:10300"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                v -> set("local_listen", v.trim(), "127.0.0.1:10300")));
        return card;
    }

    private View dailyCard() {
        LinearLayout card = section(R.string.assistant_daily, 0);
        Switch recap = new Switch(this);
        recap.setText(R.string.assistant_recap);
        recap.setTextColor(Look.INK);
        String at = text("recap_at", RECAP);
        recap.setChecked(!at.isEmpty());
        recap.setOnCheckedChangeListener((b, c) -> { if (c) a.remove("recap_at"); else HeatingActivity.put(a, "recap_at", ""); relayout(); });
        card.addView(recap);
        if (!at.isEmpty()) {
            Button time = ui.flat(getString(R.string.assistant_recap_at, at));
            time.setOnClickListener(v -> {
                int m = Math.max(0, EnergyActivity.minutes(at));
                new TimePickerDialog(this, (p, h, min) -> { set("recap_at", EnergyActivity.clock(h * 60 + min), RECAP); relayout(); }, m / 60, m % 60, true).show();
            });
            card.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)));
        }
        card.addView(ui.note(getString(R.string.assistant_recap_about)));
        List<String> speakers = HomeActivity.strings(a.optJSONArray("door_speakers"));
        List<String> names = new ArrayList<>();
        for (String p : speakers) names.add(house.pointWords(p));
        card.addView(chosen(getString(R.string.assistant_door), String.join("\n", names), null, () -> {
            if (!house.running) { banner(getString(R.string.settings_no_moli), Look.WARN); return; }
            Picker.show(this, getString(R.string.assistant_door),
                    house.pointItems(p -> p.optString("point").endsWith("/announce") || p.optString("point").endsWith("/say"),
                            p -> p.optBoolean("writable") && "text".equals(p.optString("type"))),
                    new HashSet<>(speakers), picked -> {
                        if (picked.isEmpty()) a.remove("door_speakers"); else HeatingActivity.put(a, "door_speakers", new JSONArray(picked));
                        relayout();
                    });
        }));
        card.addView(ui.note(getString(R.string.assistant_door_about)));
        Switch tv = new Switch(this);
        tv.setText(R.string.assistant_quiet_tv);
        tv.setTextColor(Look.INK);
        tv.setChecked(a.optBoolean("quiet_during_tv"));
        tv.setOnCheckedChangeListener((b, c) -> { set("quiet_during_tv", c ? Boolean.TRUE : null, null); changed(); });
        card.addView(tv, ui.matchWrap(0, ui.dp(8), 0, 0));
        return card;
    }
}
