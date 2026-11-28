package io.moli.android;

import android.widget.LinearLayout;
import android.widget.Switch;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * « Assistants IA »: what agents reach over MCP (`[mcp] disabled_tools`,
 * docs/components/api.md). A tool switched off is gone from `/mcp` and
 * `/mcp/readonly`: neither listed nor callable. Edited by people only.
 */
public final class McpActivity extends SettingsScreen {
    private JSONArray tools = new JSONArray();
    private JSONObject extra = new JSONObject();
    private List<String> off = new ArrayList<>();

    @Override protected int title() { return R.string.mcp_screen; }
    @Override protected int about() { return R.string.mcp_screen_about; }

    @Override protected void read() throws Exception {
        JSONObject sections = Setup.sections(this, "mcp").optJSONObject("sections");
        JSONArray all = Setup.mcpTools(this);
        handler.post(() -> {
            tools = all;
            JSONObject m = sections.optJSONObject("mcp");
            extra = EnergyActivity.copy(m);
            off = HomeActivity.strings(m == null ? null : m.optJSONArray("disabled_tools"));
        });
    }

    @Override protected String draft() {
        try {
            JSONObject m = EnergyActivity.copy(extra);
            List<String> ordered = new ArrayList<>();
            for (int i = 0; i < tools.length(); i++) if (off.contains(tools.optString(i))) ordered.add(tools.optString(i));
            if (ordered.isEmpty()) m.remove("disabled_tools"); else m.put("disabled_tools", new JSONArray(ordered));
            return new JSONObject().put("mcp", m.length() == 0 ? JSONObject.NULL : m).toString();
        } catch (JSONException impossible) { return null; }
    }

    @Override protected JSONObject preview(String draft) throws Exception { return Setup.sectionsPreview(this, new JSONObject(draft)); }

    @Override protected String write(String draft) throws Exception { return Setup.sectionsPut(this, new JSONObject(draft)); }

    @Override protected int saved() { return R.string.settings_saved_restarted; }

    private String words(String tool) {
        int id = getResources().getIdentifier("mcp_tool_" + tool, "string", getPackageName());
        return id == 0 ? tool : getString(id);
    }

    @Override protected void fill(LinearLayout first, LinearLayout second) {
        LinearLayout card = section(R.string.mcp_tools, R.string.mcp_tools_about);
        for (int i = 0; i < tools.length(); i++) {
            String tool = tools.optString(i);
            Switch s = new Switch(this);
            s.setText(words(tool));
            s.setTextSize(15);
            s.setTextColor(Look.INK);
            s.setChecked(!off.contains(tool));
            s.setMinHeight(ui.dp(48));
            s.setOnCheckedChangeListener((b, on) -> { if (on) off.remove(tool); else if (!off.contains(tool)) off.add(tool); changed(); });
            card.addView(s);
        }
        first.addView(card);
        LinearLayout where = section(R.string.mcp_where, R.string.mcp_where_about);
        String here = Setup.homeAddress(this);
        if (here != null) where.addView(ui.text("http://" + here + ":" + MoliService.port + "/mcp", 15, Look.INK, true));
        second.addView(where);
    }
}
