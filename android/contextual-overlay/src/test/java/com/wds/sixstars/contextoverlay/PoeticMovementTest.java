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

    private static PoeticMovement.Input fixture(boolean rate){
        return PoeticMovement.input(new CryptoWordGenerator.Words(
            java.util.Arrays.asList("mountain","sea","shadow","flame","wind","book","door","root","garden","leaf","clock","mirror"),
            java.util.Arrays.asList("reach","remember","sing","forgive","gather","mend","bind","heal","breathe","protect","carve","dream")),
            "mirth, depth, poetry",rate);
    }
    @Test public void generalTestButtonMakesTwoIndependentRequests()throws Exception{
        PoeticMovement.Input input=fixture(true);
        java.util.List<String> prompts=new java.util.ArrayList<>(),stages=new java.util.ArrayList<>();
        String selection="| Noun | Selected verb | Default verb |\n| --- | --- | --- |\n"+
            "| mountain | reach | stand |\n| sea | remember | flow |\n| shadow | sing | follow |\n| flame | forgive | burn |\n| wind | gather | blow |";
        PoeticMovement.Outcome outcome=PoeticMovement.executeGeneral(input,(operation,prompt,stats,partial)->{
            prompts.add(prompt);
            stats[6]=prompts.size();
            if(prompts.size()==1){assertEquals("Poetic selection",operation);return selection;}
            assertEquals("Poetic resolution",operation);
            return "1|The mountain reaches for the dawn.|8/10\n2|The sea remembers every shore.|9\n"+
                "3|The shadow sings in moonlight.|7\n4|The flame forgives the rain.|8\n5|The wind gathers lost wishes.|9";
        },(stage,message)->stages.add(stage));
        assertEquals(2,prompts.size());
        assertEquals(5,outcome.pairs().size());assertEquals(5,outcome.resolutions().size());
        assertEquals("stand",outcome.pairs().get(0).ordinary());
        assertEquals("The mountain reaches for the dawn.",outcome.resolutions().get(0).expression());
        assertEquals(1L,outcome.firstStats()[6]);assertEquals(2L,outcome.secondStats()[6]);
        String second=prompts.get(1);
        for(String absent:new String[]{"stand","flow","follow","burn","blow","Nouns:","Verbs:","book","mirror","ordinary","default"})
            assertFalse(absent+" leaked into request 2",second.contains(absent));
        assertTrue(second.contains("mountain | reach"));assertTrue(second.contains("wind | gather"));
        assertTrue(stages.indexOf("selected")<stages.indexOf("resolution_input"));
        assertTrue(stages.indexOf("resolution_input")<stages.indexOf("resolve"));
        String function=PoeticMovement.resolutionPrompt(PoeticMovement.resolutionInput(input,outcome.pairs()));
        for(String absent:new String[]{"stand","flow","follow","burn","blow","Nouns:","Verbs:","book","mirror","ordinary","default"})
            assertFalse(absent+" leaked into function request 2",function.contains(absent));
    }
    @Test public void acceptsNumberedFencedRows(){
        String fence="\u0060\u0060\u0060";
        String selection=fence+"text\n1. mountain|reach|stand\n2. sea|remember|flow\n3. shadow|sing|follow\n4. flame|forgive|burn\n5. wind|gather|blow\n"+fence;
        assertEquals(5,PoeticMovement.parseSelectionRows(fixture(false),selection).size());
    }
    @Test public void invalidSelectionDoesNotFabricateSecondRequest()throws Exception{
        java.util.List<String> prompts=new java.util.ArrayList<>();
        try{
            PoeticMovement.executeGeneral(fixture(false),(operation,prompt,stats,partial)->{
                prompts.add(prompt);return "mountain|reach|stand";
            },(stage,message)->{});
            fail("Incomplete selection accepted");
        }catch(IllegalArgumentException expected){
            assertTrue(expected.getMessage().contains("request 2 not started"));
        }
        assertEquals(1,prompts.size());
    }
    @Test public void secondRequestFailureIsDistinct()throws Exception{
        java.util.List<String> prompts=new java.util.ArrayList<>();
        try{
            PoeticMovement.executeGeneral(fixture(false),(operation,prompt,stats,partial)->{
                prompts.add(prompt);
                return prompts.size()==1?"1|1|stand;2|2|flow;3|3|follow;4|4|burn;5|5|blow":"invalid";
            },(stage,message)->{});
            fail("Incomplete resolution accepted");
        }catch(IllegalArgumentException expected){
            assertTrue(expected.getMessage().contains("Request 2 ran"));
        }
        assertEquals(2,prompts.size());
    }
}
