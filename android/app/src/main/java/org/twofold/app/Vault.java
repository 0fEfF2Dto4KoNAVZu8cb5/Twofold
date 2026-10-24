package org.twofold.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class Vault {
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("twofold", Context.MODE_PRIVATE); }
    private static SecretKey wrappingKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias("twofold-vault-v1")) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("twofold-vault-v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry("twofold-vault-v1", null)).getSecretKey();
    }
    public static synchronized void save(Context c, JSONObject config) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey());
        byte[] encrypted = cipher.doFinal(config.toString().getBytes(StandardCharsets.UTF_8));
        byte[] data = new byte[12 + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, data, 0, 12);
        System.arraycopy(encrypted, 0, data, 12, encrypted.length);
        if (!prefs(c).edit().putString("vault", Tokens.encode(data)).commit()) throw new java.io.IOException("Could not save phone credentials");
    }
    public static synchronized JSONObject load(Context c) throws Exception {
        String value = prefs(c).getString("vault", "");
        if (value.isEmpty()) return null;
        byte[] data = Tokens.decode(value);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), new GCMParameterSpec(128, Arrays.copyOfRange(data, 0, 12)));
        return new JSONObject(new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8));
    }
    public static boolean paired(Context c) { try { JSONObject config=load(c);return config!=null&&config.optBoolean("enrolled",false); } catch (Exception e) { return false; } }
    public static void clear(Context c) { if(!prefs(c).edit().clear().commit())throw new IllegalStateException("Could not clear saved credentials"); }
}
