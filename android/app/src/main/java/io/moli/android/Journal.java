package io.moli.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * What Moli and the app have said lately, to read, copy or send (DECISIONS 8),
 * from the home screen and from an integration's form.
 */
final class Journal {
    private Journal() {}

    static void show(Activity a) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String text;
            try { text = Setup.journal(a); } catch (Exception unreadable) { text = String.valueOf(unreadable.getMessage()); }
            String shown = text;
            main.post(() -> {
                if (a.isDestroyed()) return;
                Look ui = new Look(a);
                TextView view = ui.text(shown.isEmpty() ? a.getString(R.string.journal_empty) : shown, 11, Look.INK, false);
                view.setTypeface(Typeface.MONOSPACE);
                view.setTextIsSelectable(true);
                view.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
                ScrollView scroll = new ScrollView(a);
                scroll.addView(view);
                new AlertDialog.Builder(a)
                        .setTitle(R.string.journal_title)
                        .setView(scroll)
                        .setPositiveButton(R.string.journal_copy, (d, w) -> {
                            a.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Moli", shown));
                            Toast.makeText(a, R.string.journal_copied, Toast.LENGTH_SHORT).show();
                        })
                        .setNeutralButton(R.string.journal_share, (d, w) -> {
                            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shown);
                            a.startActivity(Intent.createChooser(send, a.getString(R.string.journal_title)));
                        })
                        .setNegativeButton(android.R.string.ok, null)
                        .show();
                // The latest lines first in view: the end.
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
        }, "journal").start();
    }
}
