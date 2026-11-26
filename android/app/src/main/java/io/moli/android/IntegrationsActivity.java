package io.moli.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Where the integrations will be configured. Empty for now. */
public final class IntegrationsActivity extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView empty = new TextView(this);
        int gap = (int) (16 * getResources().getDisplayMetrics().density);
        empty.setPadding(gap, gap, gap, gap);
        empty.setTextSize(18);
        empty.setText(R.string.integrations_empty);
        setContentView(empty);
    }
}
