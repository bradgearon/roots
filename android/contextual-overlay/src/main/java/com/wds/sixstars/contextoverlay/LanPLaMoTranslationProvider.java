package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Reuses one authenticated persistent WebSocket on the existing mobile bridge;
 * falls back to legacy HTTP only if the socket handshake fails before a
 * translation is sent. Both routes forward to local PLaMo on port 8081.
 *
 * The service requires separate consent, a paired secret, and Wi-Fi connectivity.
 * Plain HTTP is for a TRUSTED LAN only; a secure VPN/HTTPS reverse proxy is
 * recommended before using this with private text on untrusted networks.
 */
public final class LanPLaMoTranslationProvider implements TranslationProvider {
    // One in-flight call, at most three queued. Fast scrolling never creates
    // an unbounded backlog of stale requests on the desktop GPU.
    private static final ThreadPoolExecutor REQUESTS = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(3),
            r -> {
                Thread thread = new Thread(r, "SixStars-X-Translation");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Context app;
    private final OverlayPreferences preferences;

    public LanPLaMoTranslationProvider(Context context) {
        app = context.getApplicationContext();
        preferences = new OverlayPreferences(app);
    }

    @Override
    public void translate(String text, String sourceLanguageHint, String targetLanguage, Result callback) {
        if (callback == null) return;
        if (!preferences.enabled() || !preferences.desktopTranslationEnabled()) {
            callback.completed(null, "Enable desktop PLaMo translation and consent in Roots settings.");
            return;
        }
        if (text == null || text.trim().isEmpty() || text.length() > 1200) {
            callback.completed(null, "Text is empty or too long.");
            return;
        }
        if (!targetLanguage.matches("(?i)[a-z]{2,3}")) {
            callback.completed(null, "Invalid target language.");
            return;
        }
        // Copy only selected text, not an entire accessibility tree.
        final String content = text.trim();
        try { REQUESTS.execute(() -> {
            try {
                if (!preferences.enabled() || !preferences.desktopTranslationEnabled()) {
                    callback.completed(null, "Desktop translation permission is off.");
                    return;
                }
                String key = OverlayTranslationKeyStore.read(app);
                if (key.isEmpty()) {
                    callback.completed(null, "Set the desktop translation key in Roots overlay settings.");
                    return;
                }

                String base = preferences.bridgeUrl();
                URL address = new URL(base + "/api/mobile/translation");
                if (!"http".equalsIgnoreCase(address.getProtocol())
                        && !"https".equalsIgnoreCase(address.getProtocol())) {
                    callback.completed(null, "Use an http:// or https:// Roots bridge address.");
                    return;
                }

                ConnectivityManager manager =
                        (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network wifi = findWifi(manager);
                if (wifi == null) {
                    callback.completed(null, "Connect to the same trusted Wi-Fi network as Roots desktop.");
                    return;
                }

                JSONObject payload = new JSONObject();
                payload.put("text", content);
                payload.put("targetLang", targetLanguage.toLowerCase(java.util.Locale.ROOT));
                if (sourceLanguageHint != null &&
                        sourceLanguageHint.matches("(?i)[a-z]{2,3}")) {
                    payload.put("sourceLang", sourceLanguageHint.toLowerCase(java.util.Locale.ROOT));
                }
                payload.put("maxTokens", 768);

                // Default: one authenticated, long-lived WebSocket for all X posts.
                // If its handshake never succeeded (old bridge / offline route),
                // and NO translation was sent, use the existing HTTP endpoint.
                PersistentMobileTranslationSocket.Reply websocket =
                        PersistentMobileTranslationSocket.instance()
                                .translate(wifi, base, key, payload);
                if (websocket != null) {
                    callback.completed(websocket.translation, websocket.error);
                    return;
                }
                PersistentMobileTranslationSocket.instance().markHttpFallback();

                URLConnection raw = wifi.openConnection(address);
                if (!(raw instanceof HttpURLConnection)) {
                    callback.completed(null, "The configured bridge is not an HTTP service.");
                    return;
                }
                HttpURLConnection conn = (HttpURLConnection) raw;
                try {
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setUseCaches(false);
                    conn.setConnectTimeout(3500);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("X-SixStars-Token", key);

                    byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream stream = conn.getOutputStream()) {
                        stream.write(bytes);
                    }

                    int code = conn.getResponseCode();
                    if (code == 401) {
                        callback.completed(null, "Invalid desktop translation key. Re-pair from Roots settings.");
                        return;
                    }
                    if (code != 200) {
                        callback.completed(null, code == 503
                                ? "Desktop PLaMo service is unavailable."
                                : "Desktop translation HTTP " + code);
                        return;
                    }
                    String response;
                    try (InputStream stream = conn.getInputStream()) {
                        response = readLimited(stream, 96 * 1024);
                    }
                    JSONObject data = new JSONObject(response);
                    String translated = data.optString("translation", "").trim();
                    if (translated.isEmpty()) callback.completed(null, "PLaMo returned no translation.");
                    else callback.completed(translated, null);
                } finally {
                    conn.disconnect();
                }
            } catch (Exception ex) {
                // Never place source text or access tokens into exceptions or logs.
                callback.completed(null, "Cannot reach desktop PLaMo. Check bridge URL, Wi-Fi and that Roots Bridge is running.");
            }
        }); } catch (RejectedExecutionException busy) {
            callback.completed(null, "Translation queue is busy. Try again shortly.");
        }
    }

    private static Network findWifi(ConnectivityManager connectivity) {
        if (connectivity == null) return null;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                return network;
            }
        }
        return null;
    }

    private static String readLimited(InputStream stream, int limit) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int count;
        while ((count = stream.read(chunk)) >= 0) {
            if (buffer.size() + count > limit) throw new IllegalArgumentException("Response too large");
            buffer.write(chunk, 0, count);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
