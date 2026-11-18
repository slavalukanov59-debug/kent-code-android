package ai.kent.code;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local encrypted credential storage; no API key is part of the APK or GitHub. */
public final class SecureKeyStore {
    private static final String ALIAS = "kent_ai_flex_api_v1";
    private final SharedPreferences prefs;

    public SecureKeyStore(Context context) {
        prefs = context.getSharedPreferences("kent_settings", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    public void save(String secret) throws Exception {
        if (secret == null || secret.isBlank()) {
            prefs.edit().remove("api_key").apply();
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(secret.trim().getBytes(StandardCharsets.UTF_8));
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP);
        prefs.edit().putString("api_key", value).apply();
    }

    public String load() throws Exception {
        String value = prefs.getString("api_key", "");
        if (value == null || value.isEmpty()) return "";
        String[] parts = value.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("Неверное хранилище ключа");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    public String model() { return prefs.getString("model", "deepseek-v4.1-flash-1017"); }
    public void model(String m) { prefs.edit().putString("model", m.trim()).apply(); }
    public int steps() { return Math.max(1, Math.min(100, prefs.getInt("steps", 12))); }
    public void steps(int count) { prefs.edit().putInt("steps", Math.max(1, Math.min(100, count))).apply(); }
}
