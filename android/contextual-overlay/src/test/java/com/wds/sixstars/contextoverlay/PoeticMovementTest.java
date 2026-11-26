package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;
public class PoeticMovementTest {
    private static String call(String op,String key,String value){
        return "<start_function_call>call:"+op+"{"+key+":<escape>"+value+"<escape>}<end_function_call>";
    }
    @Test public void randomWordsAndTwoStages(){
        CryptoWordGenerator.Words words=CryptoWordGenerator.generate();
        PoeticMovement.Input a=PoeticMovement.input(words,"depth",true);
        assertEquals(12,a.nouns().size());assertEquals(12,a.verbs().size());
        assertEquals(12,new java.util.HashSet<>(a.nouns()).size());
        assertEquals(12,new java.util.HashSet<>(a.verbs()).size());
        assertEquals(words.nouns(),a.nouns());assertEquals(words.verbs(),a.verbs());
        String usual="xyz";
        String value="1|1|"+usual+";2|2|"+usual+";3|3|"+usual+";4|4|"+usual+";5|5|"+usual;
        java.util.List<PoeticMovement.Pair> pairs=PoeticMovement.parseSelection(a,call("select_movements","pairs",value));
        assertEquals(5,pairs.size());assertEquals(a.nouns().get(0),pairs.get(0).noun());
        assertFalse(PoeticMovement.resolutionPrompt(a,pairs).contains("Nouns:"));
        assertTrue(PoeticMovement.resolutionPrompt(a,pairs).contains("rating 1-10"));
        assertEquals(Integer.valueOf(7),PoeticMovement.parseResolution(a,call("resolve_movements","resolutions","1|one|7;2|two|8;3|three|6;4|four|9;5|five|5")).get(0).rating());
        PoeticMovement.Input no=PoeticMovement.input(CryptoWordGenerator.generate(),"",false);
        assertNull(PoeticMovement.parseResolution(no,call("resolve_movements","resolutions","1|one;2|two;3|three;4|four;5|five")).get(0).rating());
    }
    @Test(expected=IllegalArgumentException.class) public void duplicateSelectionRejected(){
        PoeticMovement.parseSelection(PoeticMovement.input(CryptoWordGenerator.generate(),"",false),call("select_movements","pairs","1|1|xyz;1|1|xyz;3|3|xyz;4|4|xyz;5|5|xyz"));
    }
}
