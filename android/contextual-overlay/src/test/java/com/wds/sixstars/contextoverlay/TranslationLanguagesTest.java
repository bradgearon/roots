package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import static org.junit.Assert.*;

public class TranslationLanguagesTest {
    @Test public void pickerHas43UniquePublishedCodesAndLabels() {
        String[] codes = TranslationLanguages.targetCodes();
        String[] labels = TranslationLanguages.targetLabels();
        assertEquals(43, codes.length);
        assertEquals(codes.length, labels.length);
        assertEquals(43, new HashSet<>(Arrays.asList(codes)).size());
        for (String code : codes) {
            assertTrue(code, TranslationLanguages.isTarget(code));
            assertTrue(code, TranslationLanguages.isModelLanguage(code));
        }
        for (String code : new String[]{"en","ja","es","fr","de","ko","he","zh-hans","zh-hant"})
            assertTrue(code, TranslationLanguages.isTarget(code));
        assertFalse(TranslationLanguages.isTarget("pt"));
        assertFalse(TranslationLanguages.isTarget("tr"));
        assertFalse(TranslationLanguages.isTarget(null));
        codes[0] = "broken";
        assertTrue(TranslationLanguages.isTarget("ar"));
    }

    @Test public void sourceLanguageRetainsChineseScriptAndCanonicalAliases() {
        assertEquals("zh-hans", PostLanguage.code("zh-Hans-CN"));
        assertEquals("zh-hant", PostLanguage.code("zh_TW"));
        assertEquals("zh-hant", PostLanguage.code("zh-Hant-CN"));
        assertEquals("zh", PostLanguage.code("zh"));
        assertEquals("", TranslationLanguages.sourceCode("zh"));
        assertEquals("zh-hant", TranslationLanguages.sourceCode("zh-Hant"));
        assertEquals("he", TranslationLanguages.sourceCode("iw"));
        assertEquals("id", TranslationLanguages.sourceCode("in"));
        assertEquals("tl", TranslationLanguages.sourceCode("fil"));
        assertEquals("et", TranslationLanguages.sourceCode("et"));
        assertEquals("", TranslationLanguages.sourceCode("pt"));
        assertEquals("", TranslationLanguages.sourceCode(null));
        assertEquals("ru", PostLanguage.code("ru-RU"));
    }
}
