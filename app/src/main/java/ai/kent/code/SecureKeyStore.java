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


    /** Checks an opaque key without logging it, before HTTP Authorization. */
    public static void validateApiKey(String value) {
        ApiKeyValidator.check(value);
    }

    // The alias is shared by all providers and activity instances.
    private static synchronized SecretKey key() throws Exception {
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
        if (secret == null || secret.trim().isEmpty()) {
            prefs.edit().remove("api_key").apply();
            return;
        }
        validateApiKey(secret.trim());
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

    public String provider() {
        // Preserve existing AI-FLEX installs without silently switching their credential.
        return prefs.getString("ai_provider", prefs.contains("api_key") ? "ai_flex" : "openrouter");
    }

    public void provider(String id) {
        if (!id.equals("ai_flex") && !id.equals("openrouter"))
            throw new IllegalArgumentException("Неподдерживаемый провайдер");
        prefs.edit().putString("ai_provider", id).apply();
    }

    public String modelFor(String id) {
        if (id.equals("openrouter"))
            return prefs.getString("openrouter_model", "anthropic/claude-sonnet-5.5");
        return prefs.getString("model", "deepseek-v4.1-flash-1017");
    }

    public String model() { return modelFor(provider()); }
    public void model(String m) {
        prefs.edit().putString(provider().equals("openrouter") ? "openrouter_model" : "model", m.trim()).apply();
    }

    public String loadActiveKey() throws Exception { return loadProviderKey(provider()); }

    public String loadProviderKey(String id) throws Exception {
        if (id.equals("ai_flex")) return load(); // existing encrypted key
        if (!id.equals("openrouter")) throw new IllegalArgumentException("Провайдер не поддерживается");
        String encrypted = prefs.getString("openrouter_key", "");
        if (encrypted == null || encrypted.isEmpty()) return "";
        String[] parts = encrypted.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("Неверный ключ OpenRouter");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    public void saveProviderKey(String id, String value) throws Exception {
        if (id.equals("ai_flex")) { save(value); return; }
        if (!id.equals("openrouter")) throw new IllegalArgumentException("Провайдер не поддерживается");
        if (value == null || value.trim().isEmpty()) {
            prefs.edit().remove("openrouter_key").apply();
            return;
        }
        validateApiKey(value.trim());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
        prefs.edit().putString("openrouter_key",
                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply();
    }

    public boolean hasGitHubToken() { return prefs.contains("github_token"); }

    public void saveGitHubToken(String token) throws Exception {
        String value = token == null ? "" : token.trim();
        SharedPreferences.Editor edit = prefs.edit();
        if (value.isEmpty()) {
            edit.remove("github_token");
        } else {
            GitHubClient.validateToken(value);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            edit.putString("github_token", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP));
        }
        if (!edit.commit()) throw new IllegalStateException("Не удалось сохранить настройки токена GitHub.");
    }

    public String loadGitHubToken() throws Exception {
        String packed = prefs.getString("github_token", "");
        if (packed == null || packed.isEmpty()) return "";
        String[] parts = packed.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("Не удалось прочитать сохранённый токен GitHub.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    /** Loopback token encrypted with the same AndroidKeystore AES key as API credentials. */
    public void saveBridgeToken(String value) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            prefs.edit().remove("bridge_token").apply();
            return;
        }
        if (!value.trim().matches("[0-9a-f]{48}"))
            throw new IllegalArgumentException("Токен должен содержать 48 символов 0-9/a-f");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
        String packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP);
        prefs.edit().putString("bridge_token", packed).apply();
    }

    public String bridgeToken() throws Exception {
        String value = prefs.getString("bridge_token", "");
        if (value == null || value.isEmpty()) return "";
        String[] parts = value.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("Неверный формат токена");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    public boolean bridgeEnabled() { return prefs.getBoolean("termux_bridge_enabled", false); }
    public void bridgeEnabled(boolean enabled) { prefs.edit().putBoolean("termux_bridge_enabled", enabled).apply(); }

    /** Explicit opt-in for text writes within the default workspace on a new mission. */
    public boolean autopilotEnabled() { return prefs.getBoolean("autopilot_enabled", false); }
    public void autopilotEnabled(boolean enabled) {
        prefs.edit().putBoolean("autopilot_enabled", enabled).apply();
    }

}
