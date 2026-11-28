package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class PostLanguageTest {
    @Test public void normalizeStylizedLatinForTheModelOnly(){
        assertEquals("Gerry",PostLanguage.clean("𝘎𝘦𝘳𝘳𝘺"));
        assertEquals("Your peace deserves a quiet home.",PostLanguage.clean("Your peace deserves a quiet home. #日本語"));
    }
    @Test public void aConflictingAttributeCannotOverruleModel(){
        assertEquals("en",PostLanguage.resolve("A quiet morning on the coast.","ja","en",.98,true));
        assertEquals("ja",PostLanguage.resolve("気をつけます!! 忘れずつけます!!","en","ja",.98,true));
        assertEquals("",PostLanguage.hint("こんにちは",""));
        assertEquals("en",PostLanguage.hint("こんにちは","en"));
    }
    @Test public void uncertainClassificationDoesNotInventALanguage(){
        assertEquals("",new PostLanguage.Detection("fr",.52,.06,true).accepted(false));
        assertEquals("fr",new PostLanguage.Detection("fr",.83,.30,true).accepted(true));
        assertEquals("ja",new PostLanguage.Detection("ja",.97,.94,true).accepted(true));
        assertEquals("",PostLanguage.resolve("This is an English post.","en","fr",.52,false));
        assertEquals(0,PostLanguage.letters("🐓🥚 123"));
    }
}
