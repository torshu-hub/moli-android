package io.moli.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * Moli's master key and the house code, encrypted by a Keystore key. The
 * Keystore key never leaves the Keystore and is never Moli's key.
 */
final class ProtectedKey {
    private static final String ALIAS = "moli.master.wrap.v1";

    private ProtectedKey() {}

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "moli-key.wrap"));
    }

    static boolean exists(Context context) {
        return file(context).getBaseFile().exists();
    }

    /** A new house: a random master key, and the code the owner chose. */
    static synchronized void create(Context context, String pin) throws Exception {
        if (exists(context)) throw new IllegalStateException("key exists");
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        StringBuilder master = new StringBuilder(64);
        for (byte b : raw) master.append(String.format(Locale.ROOT, "%02x", b & 255));
        KeyStore store = keyStore();
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, (SecretKey) store.getKey(ALIAS, null));
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        String record = new JSONObject().put("master", master.toString()).put("pin", pin).toString();
        byte[] ciphertext = cipher.doFinal(record.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        ByteBuffer bytes = ByteBuffer.allocate(4 + iv.length + ciphertext.length);
        bytes.putInt(iv.length).put(iv).put(ciphertext);
        AtomicFile target = file(context);
        FileOutputStream output = target.startWrite();
        try {
            output.write(bytes.array());
            target.finishWrite(output);
        } catch (Exception failure) {
            target.failWrite(output);
            throw failure;
        }
    }

    /** {master key, house code}. */
    static synchronized String[] open(Context context) throws Exception {
        ByteBuffer bytes = ByteBuffer.wrap(file(context).readFully());
        byte[] iv = new byte[bytes.getInt()];
        bytes.get(iv);
        byte[] ciphertext = new byte[bytes.remaining()];
        bytes.get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, (SecretKey) keyStore().getKey(ALIAS, null), new GCMParameterSpec(128, iv));
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        JSONObject record = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        return new String[] {record.getString("master"), record.getString("pin")};
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }
}
