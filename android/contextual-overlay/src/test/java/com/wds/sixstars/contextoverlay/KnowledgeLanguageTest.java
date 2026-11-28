package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;
public class KnowledgeLanguageTest {
    @Test public void englishBody(){assertEquals("en",KnowledgeLanguage.englishHint("The meeting is on Monday."));}
    @Test public void unknownAndOtherLanguages(){
        assertEquals("",KnowledgeLanguage.englishHint("Hello"));
        assertEquals("",KnowledgeLanguage.englishHint("https://example.com/the/with/you"));
        assertEquals("",KnowledgeLanguage.englishHint("Je suis avec vous dans la maison."));
        assertEquals("",KnowledgeLanguage.englishHint("これは日本語です"));
    }
}
