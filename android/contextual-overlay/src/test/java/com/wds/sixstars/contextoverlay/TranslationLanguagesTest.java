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

    @Test public void systemLanguageFollowsOrderedSupportedLocales() {
        assertEquals("ja", TranslationLanguages.systemTarget("ja-JP"));
        assertEquals("es", TranslationLanguages.systemTarget("es-MX"));
        assertEquals("he", TranslationLanguages.systemTarget("he-IL"));
        assertEquals("zh-hant", TranslationLanguages.systemTarget("zh-Hant-TW"));
        assertEquals("zh-hans", TranslationLanguages.systemTarget("zh-CN"));
        assertEquals("zh-hans", TranslationLanguages.systemTarget("zh"));
        assertEquals("fr", TranslationLanguages.systemTarget("pt-BR", "fr-FR"));
        assertEquals("en", TranslationLanguages.systemTarget("pt-BR", "tr-TR"));
        assertEquals("en", TranslationLanguages.systemTarget());
        assertEquals(44, TranslationLanguages.settingCodes().length);
        assertEquals(44, TranslationLanguages.settingLabels("Follow Android language").length);
        assertEquals("system", TranslationLanguages.settingCodes()[0]);
        assertTrue(TranslationLanguages.isTargetSetting("system"));
        assertFalse(TranslationLanguages.isModelLanguage("system"));
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
