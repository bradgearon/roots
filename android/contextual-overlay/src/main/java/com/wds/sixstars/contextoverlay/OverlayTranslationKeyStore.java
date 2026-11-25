package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The desktop's translation token is encrypted with a non-exportable Android Keystore
 * AES-GCM key. No plaintext token is stored in app preferences or backed up.
 * If Android restores preferences without the local Keystore key, pair again.
 */
public final class OverlayTranslationKeyStore {
    private static final String STORE = "contextual_overlay_secret";
    private static final String ALIAS = "six_stars_x_translation_key_v1";
    private static final String IV = "translation_key_iv";
    private static final String CIPHERTEXT = "translation_key_ciphertext";

    private OverlayTranslationKeyStore() { }

    public static synchronized void save(Context context, String token) throws Exception {
        String value = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Desktop translation key must be 64 hexadecimal characters.");
        }

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey key;
        if (keyStore.containsAlias(ALIAS)) {
            key = ((KeyStore.SecretKeyEntry) keyStore.getEntry(ALIAS, null)).getSecretKey();
        } else {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            key = generator.generateKey();
        }

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit()
                .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .apply();
    }

    public static synchronized String read(Context context) {
        try {
            SharedPreferences preferences = context.getApplicationContext()
                    .getSharedPreferences(STORE, Context.MODE_PRIVATE);
            String iv = preferences.getString(IV, "");
            String encrypted = preferences.getString(CIPHERTEXT, "");
            if (iv == null || encrypted == null || iv.isEmpty() || encrypted.isEmpty()) return "";

            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            KeyStore.Entry entry = store.getEntry(ALIAS, null);
            if (!(entry instanceof KeyStore.SecretKeyEntry)) return "";
            SecretKey key = ((KeyStore.SecretKeyEntry) entry).getSecretKey();

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            String value = new String(cipher.doFinal(
                    Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.US_ASCII);
            return value.matches("[0-9a-f]{64}") ? value : "";
        } catch (Exception e) {
            // No unsafe plaintext fallback if Android Keystore is unavailable.
            return "";
        }
    }

    public static boolean isConfigured(Context context) {
        return !read(context).isEmpty();
    }

    public static synchronized void clear(Context context) {
        context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit().remove(IV).remove(CIPHERTEXT).apply();
    }
}
