package io.moli.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.system.Os;
import java.io.File;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the process alive while Moli runs (Moli itself is the official
 * engine), and holds what a home server needs on Android: the CPU awake, the
 * Wi-Fi on and multicast heard (discovery) while the screen is off.
 */
public final class MoliService extends Service {
    static final String STOP = "io.moli.android.STOP";
    /** One queue: a start and a stop never overlap. */
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    static volatile boolean starting, stopping;
    static volatile int port;
    /** A start the engine refused (null when it started). */
    static volatile String refused;

    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private WifiManager.MulticastLock multicast;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!starting && !stopping && !NativeServer.running()) {
                release();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            handler.postDelayed(this, 2000);
        }
    };

    static File config(Context context) {
        return new File(new File(context.getFilesDir(), "moli"), "moli.toml");
    }

    /** Starts Moli if the house exists (its key is kept). */
    static void start(Context context) {
        if (ProtectedKey.exists(context))
            context.startForegroundService(new Intent(context, MoliService.class));
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
        } else if (!NativeServer.running() && !starting) {
            starting = true;
            hold();
            WORK.execute(() -> {
                try {
                    // The engine takes the system's zone from TZ, as in a container:
                    // an Android process has neither TZ nor /etc/localtime.
                    Os.setenv("TZ", TimeZone.getDefault().getID(), true);
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
        // Killed by the system: started again, Moli with it.
        return START_STICKY;
    }

    private void hold() {
        if (cpu == null) {
            cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moli:server");
            cpu.setReferenceCounted(false);
            WifiManager manager = getApplicationContext().getSystemService(WifiManager.class);
            wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "moli:server");
            wifi.setReferenceCounted(false);
            multicast = manager.createMulticastLock("moli:discovery");
            multicast.setReferenceCounted(false);
        }
        cpu.acquire();
        wifi.acquire();
        multicast.acquire();
    }

    private void release() {
        if (cpu == null) return;
        cpu.release();
        wifi.release();
        multicast.release();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(watch);
        release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
