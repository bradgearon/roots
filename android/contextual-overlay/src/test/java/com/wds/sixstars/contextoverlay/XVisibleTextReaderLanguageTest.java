package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** The reader cannot invent a language before a classifier has run. */
public class XVisibleTextReaderLanguageTest {
    @Test public void rawTextDoesNotBecomeALanguageAttribute(){
        for(String value:new String[]{"手のひらドリル","안녕하세요","مرحبا بالجميع",
            "The meeting is on Monday.","Bonjour à tous.","𝘎𝘦𝘳𝘳𝘺"})
            assertEquals("",XVisibleTextReader.languageHint(value));
    }
    @Test public void bodyLanguageRemainsUnknownUntilClassification(){
        assertEquals("",XVisibleTextReader.bodyLanguageHint("The meeting is on Monday."));
        assertEquals("",XVisibleTextReader.bodyLanguageHint("こんにちは"));
    }
}
