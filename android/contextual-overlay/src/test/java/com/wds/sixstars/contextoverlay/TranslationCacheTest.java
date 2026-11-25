package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationCacheTest {
    @Test public void cacheKeysIncludeTargetLanguage() {
        TranslationCache cache = new TranslationCache();
        cache.write("Bonjour à tous!", "en", "Hello everyone!");
        assertEquals("Hello everyone!", cache.read("  Bonjour   à tous! ", "en"));
        assertNull(cache.read("Bonjour à tous!", "de"));
    }
    @Test public void evictsLeastRecentlyUsedAndCanClear() {
        TranslationCache cache = new TranslationCache();
        cache.write("old", "en", "first");
        for (int i = 0; i < 100; i++) cache.write("line-" + i, "en", "value-" + i);
        assertNull(cache.read("old", "en"));
        assertEquals("value-99", cache.read("line-99", "en"));
        cache.clear();
        assertNull(cache.read("line-99", "en"));
    }
}
