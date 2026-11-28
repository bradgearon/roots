package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class PostLanguageTest {
    @Test public void hashtagAndSingleForeignCharacterDoNotSetLanguage(){
        assertEquals("en",PostLanguage.hint("This is what I wanted to tell you. 猫 #日本語",""));
        assertEquals("en",PostLanguage.hint("This is what I wanted to tell you. @example #日本語","ja"));
    }
    @Test public void dominantScriptCanCorrectAnEnglishHint(){
        assertEquals("ja",PostLanguage.hint("今日もよろしくお願いします。","en"));
        assertEquals("ko",PostLanguage.hint("오늘은 좋은 날입니다","en"));
    }
    @Test public void reliableFastTextCanCorrectAnUncertainLatinHint(){
        assertEquals("en",PostLanguage.resolve("A quiet morning on the coast.","fr","en",.98,true));
        assertEquals("fr",PostLanguage.resolve("Nous allons partir demain matin.","en","fr",.97,true));
        assertEquals("ja",PostLanguage.resolve("今日もよろしくお願いします。","ja","en",.80,true));
    }
    @Test public void uncertainDetectionDoesNotOverrideLocalEvidence(){
        assertEquals("en",PostLanguage.resolve("This is what I wanted to tell you.","en","fr",.52,false));
        assertEquals(0,PostLanguage.letters("🐓🥚 123"));
    }
}
