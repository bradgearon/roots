package com.wds.sixstars.contextoverlay;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Heuristic language detection smoke tests for the X reader. */
public class XVisibleTextReaderLanguageTest {
    @Test public void recognizesShortJapanesePosts() {
        assertEquals("ja", XVisibleTextReader.languageHint("手のひらドリル"));
        assertEquals("ja", XVisibleTextReader.languageHint("ときおかりぬいぐるみ"));
    }

    @Test public void recognizesCommonNonLatinScripts() {
        assertEquals("ko", XVisibleTextReader.languageHint("안녕하세요"));
        assertEquals("ar", XVisibleTextReader.languageHint("مرحبا بالجميع"));
    }

    @Test public void doesNotInventLanguageForEnglish() {
        assertEquals("", XVisibleTextReader.languageHint("The meeting is on Monday."));
    }
}
