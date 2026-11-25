package com.wds.sixstars.contextoverlay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Fingerprints content rather than keeping AccessibilityNodeInfo references
 * across async responses. No raw text is written to disk.
 */
public final class TextFingerprint {
    private TextFingerprint() { }

    public static String normalized(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ").trim();
    }

    public static String of(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte v : digest) hex.append(String.format(Locale.ROOT, "%02x", v & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
