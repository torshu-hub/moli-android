package io.moli.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps the process alive while Moli runs. Moli itself is the official engine. */
public final class MoliService extends Service {
    static final String STOP = "io.moli.android.STOP";
    /** One queue: a start and a stop never overlap. */
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    static volatile boolean starting, stopping;
    static volatile int port;
    /** A start the engine refused (null when it started). */
    static volatile String refused;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!starting && !stopping && !NativeServer.running()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            handler.postDelayed(this, 2000);
        }
    };

    static File config(android.content.Context context) {
        return new File(new File(context.getFilesDir(), "moli"), "moli.toml");
    }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("moli", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, ManagementActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "moli")
                .setSmallIcon(R.drawable.ic_moli)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_running))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(1, notification);
        if (intent != null && STOP.equals(intent.getAction())) {
            stopping = true;
            WORK.execute(() -> {
                try { NativeServer.stop(); } catch (RuntimeException ignored) { /* shown by failure() */ }
                finally { stopping = false; }
            });
        } else {
            starting = true;
            WORK.execute(() -> {
                try {
                    String[] key = ProtectedKey.open(this);
                    port = NativeServer.start(config(this).getAbsolutePath(), key[0], key[1]);
                    refused = null;
                } catch (IllegalStateException failure) {
                    refused = failure.getMessage();
                } catch (Exception failure) {
                    refused = getString(R.string.key_failed);
                } finally {
                    starting = false;
                }
            });
        }
        handler.removeCallbacks(watch);
        handler.postDelayed(watch, 2000);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(watch);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
