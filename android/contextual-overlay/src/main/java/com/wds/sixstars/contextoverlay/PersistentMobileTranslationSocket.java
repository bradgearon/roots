package com.wds.sixstars.contextoverlay;

import android.net.Network;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * One shared, long-lived WebSocket between the Android overlay and the desktop
 * mobile bridge. A single RFC 6455 upgrade authenticates the session; all
 * subsequent translation requests use small request-ID-tagged JSON frames.
 *
 * The extension point is still TranslationProvider, so this transport can move
 * into a separate Android app without depending on MainActivity.
 *
 * A null result means the request was NEVER put on the socket and may safely
 * fall back to the older HTTP translation endpoint. Once a request is sent,
 * a disconnect/timeout reports an error instead of repeating GPU work.
 */
final class PersistentMobileTranslationSocket {
    private static final PersistentMobileTranslationSocket INSTANCE =
            new PersistentMobileTranslationSocket();
    private static final int CONNECT_TIMEOUT_MS = 3800;
    private static final int TRANSLATE_TIMEOUT_MS = 29000;
    private static final int MAX_PENDING = 4;
    private static final long RETRY_MS = 2500L;
    private static final long OLD_BRIDGE_RETRY_MS = 60000L;

    private final Object mutex = new Object();
    private final AtomicInteger nextRequest = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, Pending> pending = new ConcurrentHashMap<>();

    private WebSocket socket;
    private CountDownLatch connected;
    private OkHttpClient client;
    private Network currentNetwork;
    private String currentUrl;
    private String currentTokenHash;
    private boolean ready;
    private int handshakeStatus;
    private long retryAfterMs;
    private volatile String status = "Not connected";

    static PersistentMobileTranslationSocket instance() { return INSTANCE; }
    String status() { return status; }

    /**
     * A null return is an explicit HTTP-fallback permission; a Reply must be
     * delivered to the caller without making another network request.
     */
    Reply translate(Network wifi, String base, String token, JSONObject payload) {
        WebSocket active;
        CountDownLatch handshake;
        boolean reused;
        try {
            String address = socketUrl(base);
            String fingerprint = hashToken(token);
            synchronized (mutex) {
                long now = System.currentTimeMillis();
                boolean changedConfiguration =
                        !Objects.equals(currentUrl, address)
                        || !Objects.equals(currentTokenHash, fingerprint)
                        || !Objects.equals(currentNetwork, wifi);
                if (!changedConfiguration && retryAfterMs > now && socket == null)
                    return null;

                boolean same = socket != null && !changedConfiguration;
                if (!same) {
                    boolean changedNetwork = !Objects.equals(currentNetwork, wifi);
                    dropLocked("Socket connection changed", true);
                    retryAfterMs = 0L;
                    currentUrl = address;
                    currentTokenHash = fingerprint;
                    currentNetwork = wifi;
                    if (client == null || changedNetwork) {
                        client = makeClient(wifi);
                    }
                    connected = new CountDownLatch(1);
                    ready = false;
                    handshakeStatus = 0;
                    Request request = new Request.Builder().url(address)
                            .header("X-SixStars-Token", token)
                            .build();
                    socket = client.newWebSocket(request, new Listener(connected));
                    status = "Connecting WebSocket";
                }
                active = socket;
                handshake = connected;
                reused = same && ready;
            }

            if (active == null || handshake == null) return null;
            if (!handshake.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                failConnect(active, 0);
                return null;
            }
            synchronized (mutex) {
                if (socket != active || !ready) {
                    if (handshakeStatus == 401) {
                        return new Reply(null, "Desktop translation key was rejected.");
                    }
                    return null; // No translation frame has been sent.
                }
                status = reused ? "WebSocket · reused" : "WebSocket · connected";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(null, "Translation interrupted.");
        } catch (Exception e) {
            return null; // Cannot open socket; HTTP fallback remains available.
        }

        if (pending.size() >= MAX_PENDING) {
            return new Reply(null, "Translation socket is busy.");
        }
        int id = nextId();
        Pending operation = new Pending();
        pending.put(id, operation);

        try {
            JSONObject message = new JSONObject()
                    .put("type", "translate")
                    .put("id", Integer.toString(id));
            java.util.Iterator<String> keys = payload.keys();
            while (keys.hasNext()) { String k = keys.next(); message.put(k, payload.get(k)); }
            if (!active.send(message.toString())) {
                pending.remove(id);
                failConnect(active, 0);
                return null; // OkHttp did not queue this message.
            }

            if (!operation.done.await(TRANSLATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                pending.remove(id);
                try {
                    active.send(new JSONObject().put("type", "cancel")
                            .put("id", Integer.toString(id)).toString());
                } catch (Exception ignored) { }
                return new Reply(null, "PLaMo translation timed out.");
            }
            return operation.answer == null
                    ? new Reply(null, "Desktop socket disconnected.")
                    : operation.answer;
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            return new Reply(null, "Translation interrupted.");
        } catch (Exception e) {
            pending.remove(id);
            return new Reply(null, "Translation socket request failed.");
        }
    }

    /**
     * Socket-specific bridge URL, preserving the configured desktop host/port.
     * No token is placed in the URL, fragment or query string.
     */
    static String socketUrl(String bridge) {
        if (bridge == null) throw new IllegalArgumentException("Bridge URL required");
        String normalized = bridge.trim().replaceAll("/+$", "");
        if (normalized.startsWith("https://"))
            return "wss://" + normalized.substring(8) + "/api/translation/v1/socket";
        if (normalized.startsWith("http://"))
            return "ws://" + normalized.substring(7) + "/api/translation/v1/socket";
        throw new IllegalArgumentException("Unsupported bridge URL");
    }

    void markHttpFallback() {
        status = "HTTP fallback";
    }

    void disconnect() {
        synchronized (mutex) {
            dropLocked("Translation overlay disconnected", true);
            status = "Not connected";
            retryAfterMs = 0L;
        }
    }

    private static OkHttpClient makeClient(Network wifi) {
        return new OkHttpClient.Builder()
                .socketFactory(wifi.getSocketFactory())
                .dns(hostname -> Arrays.asList(wifi.getAllByName(hostname)))
                .connectTimeout(4, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    private int nextId() {
        int id = nextRequest.getAndIncrement();
        if (id <= 0) {
            nextRequest.set(2);
            return 1;
        }
        return id;
    }

    private static String hashToken(String token) throws Exception {
        byte[] data = MessageDigest.getInstance("SHA-256")
                .digest(token.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        StringBuilder value = new StringBuilder();
        for (byte b : data) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private void failConnect(WebSocket failing, int code) {
        synchronized (mutex) {
            if (socket != failing) return;
            handshakeStatus = code;
            retryAfterMs = System.currentTimeMillis()
                    + (code == 404 ? OLD_BRIDGE_RETRY_MS : RETRY_MS);
            dropLocked("Desktop WebSocket unavailable", false);
            status = code == 401 ? "WebSocket key rejected"
                    : "WebSocket unavailable — HTTP fallback";
        }
    }

    private void dropLocked(String reason, boolean resetConfig) {
        WebSocket old = socket;
        socket = null;
        ready = false;
        if (connected != null) connected.countDown();
        connected = null;
        if (resetConfig) {
            currentUrl = null;
            currentTokenHash = null;
            currentNetwork = null;
        }
        if (old != null) {
            try { old.cancel(); } catch (Exception ignored) { }
        }
        for (Pending p : pending.values())
            p.complete(new Reply(null, reason));
        pending.clear();
    }

    private final class Listener extends WebSocketListener {
        private final CountDownLatch latch;
        Listener(CountDownLatch latch) { this.latch = latch; }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject message = new JSONObject(text);
                String type = message.optString("type", "");
                if ("auth".equals(type)) {
                    if (!message.optBoolean("ok", false)) {
                        failConnect(ws, 0);
                        return;
                    }
                    synchronized (mutex) {
                        if (socket != ws) return;
                        ready = true;
                        handshakeStatus = 101;
                        status = "WebSocket · connected";
                    }
                    latch.countDown();
                    return;
                }

                if (!"result".equals(type)) return;
                int requestId = message.optInt("id", -1);
                Pending op = pending.remove(requestId);
                if (op == null) return;
                if (!message.optBoolean("ok", false)) {
                    op.complete(new Reply(null,
                            "Desktop translation: " + message.optString("error", "unknown_error")));
                    return;
                }
                JSONObject answer = message;
                String translated = answer == null ? "" : answer.optString("translation", "");
                op.complete(translated.trim().isEmpty()
                        ? new Reply(null, "PLaMo returned an empty translation.")
                        : new Reply(translated.trim(), null));
            } catch (Exception ignored) { /* Unexpected frame; keep the socket alive. */ }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable failure, Response response) {
            failConnect(ws, response == null ? 0 : response.code());
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            failConnect(ws, 0);
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            failConnect(ws, 0);
        }
    }

    static final class Reply {
        final String translation;
        final String error;
        Reply(String translation, String error) {
            this.translation = translation;
            this.error = error;
        }
    }

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        volatile Reply answer;
        void complete(Reply value) {
            answer = value;
            done.countDown();
        }
    }
}
