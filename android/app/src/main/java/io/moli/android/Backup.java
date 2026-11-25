package io.moli.android;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A copy of the house (Moli's data folder) to a file the owner chooses, and
 * back. Moli is stopped meanwhile, so nothing is half written, then started
 * again. Like the official `moli-os backup`, the master key is never in it:
 * it is kept apart (management screen, « Clé maître et code »).
 */
final class Backup {
    /** Largest backup read back (the house's history included). */
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    private Backup() {}

    static File data(Context context) {
        return MoliService.config(context).getParentFile();
    }

    /** Stops Moli and waits for it; true when it was running. */
    static boolean stop(Context context) throws InterruptedException {
        if (!NativeServer.running()) return false;
        context.startForegroundService(new Intent(context, MoliService.class).setAction(MoliService.STOP));
        for (int i = 0; i < 300 && (NativeServer.running() || MoliService.stopping); i++) Thread.sleep(100);
        return true;
    }

    static void save(Context context, Uri target) throws Exception {
        boolean running = stop(context);
        try (OutputStream out = context.getContentResolver().openOutputStream(target, "wt");
             ZipOutputStream zip = new ZipOutputStream(out)) {
            File root = data(context);
            add(zip, root, "");
        } finally {
            if (running) MoliService.start(context);
        }
    }

    private static void add(ZipOutputStream zip, File dir, String prefix) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[65536];
        for (File file : files) {
            String name = prefix + file.getName();
            if (file.isDirectory()) {
                add(zip, file, name + "/");
                continue;
            }
            zip.putNextEntry(new ZipEntry(name));
            try (InputStream in = new FileInputStream(file)) {
                for (int n; (n = in.read(buffer)) > 0; ) zip.write(buffer, 0, n);
            }
            zip.closeEntry();
        }
    }

    /**
     * The house in `source` in place of this one, with the key and code it
     * was kept with. The previous folder is kept aside (`moli.avant-…`), never
     * deleted.
     */
    static void restore(Context context, Uri source, String master, String pin) throws Exception {
        File data = data(context);
        File incoming = new File(data.getParentFile(), "moli.restauration");
        deleteTree(incoming);
        if (!incoming.mkdirs()) throw new IllegalStateException("folder");
        long total = 0;
        boolean configured = false;
        try (InputStream in = context.getContentResolver().openInputStream(source);
             ZipInputStream zip = new ZipInputStream(in)) {
            byte[] buffer = new byte[65536];
            String base = incoming.getCanonicalPath() + File.separator;
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                File file = new File(incoming, entry.getName());
                // A name that leaves the folder (`../`) is refused, whole.
                if (!file.getCanonicalPath().startsWith(base)) throw new IllegalStateException("entry");
                if (entry.isDirectory()) { file.mkdirs(); continue; }
                file.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(file)) {
                    for (int n; (n = zip.read(buffer)) > 0; ) {
                        total += n;
                        if (total > MAX_BYTES) throw new IllegalStateException("size");
                        out.write(buffer, 0, n);
                    }
                }
                if (entry.getName().equals("moli.toml")) configured = true;
            }
        } catch (Exception failure) {
            deleteTree(incoming);
            throw failure;
        }
        if (!configured) {
            deleteTree(incoming);
            throw new IllegalStateException("not a Moli backup");
        }
        stop(context);
        if (data.exists() && !data.renameTo(new File(data.getParentFile(), "moli.avant-" + System.currentTimeMillis())))
            throw new IllegalStateException("move");
        if (!incoming.renameTo(data)) throw new IllegalStateException("move");
        ProtectedKey.replace(context, master, pin);
        MoliService.refused = null;
        MoliService.start(context);
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
