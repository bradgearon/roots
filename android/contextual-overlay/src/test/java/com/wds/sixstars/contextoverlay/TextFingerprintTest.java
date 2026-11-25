package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;

public class TextFingerprintTest {
    @Test public void normalizesWhitespaceWithoutChangingLetters() {
        assertEquals(TextFingerprint.of(" Bonjour    à tous! \n"),
                TextFingerprint.of("Bonjour à tous!"));
        assertNotEquals(TextFingerprint.of("Bonjour à tous!"),
                TextFingerprint.of("Bonsoir à tous!"));
    }
    @Test public void preservesUnicodeAndProducesDeterministicHash() {
        String one = TextFingerprint.of("こんにちは世界");
        assertEquals(64, one.length());
        assertEquals(one, TextFingerprint.of("こんにちは世界"));
        assertNotEquals(one, TextFingerprint.of("こんにちは"));
    }
}
