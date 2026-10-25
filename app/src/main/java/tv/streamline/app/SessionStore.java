package tv.streamline.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Tokens stay on this device, encrypted with a non-exportable Android Keystore key. */
final class SessionStore implements TwitchApi.Store {
    private static final String ALIAS = "streamline-session";
    private final SharedPreferences prefs;

    SessionStore(Context context) {
        prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    @Override public JSONObject load() {
        try {
            String value = prefs.getString("tokens", "");
            if (value.isEmpty()) return new JSONObject();
            String[] parts = value.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception error) {
            // A temporary Keystore failure must not destroy the only saved session.
            diagnostic("session_load_failed:" + error.getClass().getSimpleName());
            return new JSONObject();
        }
    }

    @Override public void diagnostic(String event) {
        // Fixed event codes only: never persist tokens, response bodies or exception messages.
        prefs.edit().putString("last_session_event", System.currentTimeMillis() + ":" + event).commit();
    }

    @Override public void save(JSONObject tokens) throws Exception {
        if (tokens.length() == 0) {
            if (!prefs.edit().remove("tokens").commit()) throw new Exception("Impossible d’effacer la session.");
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(tokens.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!prefs.edit().putString("tokens", value).commit()) throw new Exception("Impossible d’enregistrer la session.");
    }
}
