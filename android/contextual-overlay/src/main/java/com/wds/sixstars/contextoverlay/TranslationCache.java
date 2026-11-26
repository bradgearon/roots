package com.wds.sixstars.contextoverlay;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded private in-memory cache, no cross-session or persistent tweet storage. */
public final class TranslationCache {
    private static final int MAX_ENTRIES = 512;
    private static final long TTL_MS = 30L * 60L * 1000L;
    private final LinkedHashMap<String, Entry> values = new LinkedHashMap<>(32, 0.75f, true);

    public synchronized String read(String text, String language) {
        String key = TextFingerprint.of(text) + ":" + language;
        Entry value = values.get(key);
        if (value == null) return null;
        if (System.currentTimeMillis() - value.timeMs > TTL_MS) {
            values.remove(key);
            return null;
        }
        return value.translation;
    }

    public synchronized void write(String text, String language, String translation) {
        values.put(TextFingerprint.of(text) + ":" + language,
                new Entry(translation, System.currentTimeMillis()));
        while (values.size() > MAX_ENTRIES) {
            String eldest = values.keySet().iterator().next();
            values.remove(eldest);
        }
    }

    public synchronized void clear() { values.clear(); }

    private static final class Entry {
        final String translation;
        final long timeMs;
        Entry(String translation, long timeMs) {
            this.translation = translation;
            this.timeMs = timeMs;
        }
    }
}
