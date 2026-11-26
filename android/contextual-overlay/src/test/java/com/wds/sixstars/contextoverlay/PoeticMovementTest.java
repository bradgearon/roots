package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;
public class PoeticMovementTest {
    private static final String SEED="000102030405060708090a0b0c0d0e0f";
    private static String call(String op,String key,String value){
        return "<start_function_call>call:"+op+"{"+key+":<escape>"+value+"<escape>}<end_function_call>";
    }
    @Test public void seedReproducibleAndTwoStages(){
        PoeticMovement.Input a=PoeticMovement.input(SEED,"depth",true),b=PoeticMovement.input(SEED,"depth",true);
        assertEquals(a.nouns(),b.nouns());assertEquals(a.verbs(),b.verbs());assertEquals(12,a.nouns().size());
        String usual="xyz";
        String value="1|1|"+usual+";2|2|"+usual+";3|3|"+usual+";4|4|"+usual+";5|5|"+usual;
        java.util.List<PoeticMovement.Pair> pairs=PoeticMovement.parseSelection(a,call("select_movements","pairs",value));
        assertEquals(5,pairs.size());assertEquals(a.nouns().get(0),pairs.get(0).noun());
        assertFalse(PoeticMovement.resolutionPrompt(a,pairs).contains("Nouns:"));
        assertTrue(PoeticMovement.resolutionPrompt(a,pairs).contains("rating 1-10"));
        assertEquals(Integer.valueOf(7),PoeticMovement.parseResolution(a,call("resolve_movements","resolutions","1|one|7;2|two|8;3|three|6;4|four|9;5|five|5")).get(0).rating());
        PoeticMovement.Input no=PoeticMovement.input(SEED,"",false);
        assertNull(PoeticMovement.parseResolution(no,call("resolve_movements","resolutions","1|one;2|two;3|three;4|four;5|five")).get(0).rating());
    }
    @Test(expected=IllegalArgumentException.class) public void duplicateSelectionRejected(){
        PoeticMovement.parseSelection(PoeticMovement.input(SEED,"",false),call("select_movements","pairs","1|1|xyz;1|1|xyz;3|3|xyz;4|4|xyz;5|5|xyz"));
    }
}
