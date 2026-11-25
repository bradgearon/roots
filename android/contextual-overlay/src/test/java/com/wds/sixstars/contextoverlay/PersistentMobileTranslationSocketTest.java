package com.wds.sixstars.contextoverlay;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Tests URL construction without connecting to a real desktop or leaking keys. */
public final class PersistentMobileTranslationSocketTest {
    @Test public void convertsBridgeHttpToPersistentSocket() {
        assertEquals("ws://192.168.1.179:8066/api/translation/v1/socket",
                PersistentMobileTranslationSocket.socketUrl("http://192.168.1.179:8066"));
        assertEquals("ws://grasshoppah:8066/api/translation/v1/socket",
                PersistentMobileTranslationSocket.socketUrl("http://grasshoppah:8066/"));
    }

    @Test public void convertsSecureBridgeToWss() {
        assertEquals("wss://example.local/api/translation/v1/socket",
                PersistentMobileTranslationSocket.socketUrl("https://example.local"));
    }

    @Test public void rejectsUnknownSchemes() {
        try {
            PersistentMobileTranslationSocket.socketUrl("ftp://example.local");
            fail("Unsupported transport should not be accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void doesNotPutCredentialsInUrl() {
        String url = PersistentMobileTranslationSocket.socketUrl("http://192.168.1.179:8066");
        if (url.contains("token") || url.contains("key=")) {
            fail("Translation access tokens belong only in the authenticated upgrade header.");
        }
    }
}
