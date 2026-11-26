package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class FunctionGemmaProtocolTest {
    private static String call(String body){return "<start_function_call>call:"+body+"<end_function_call>";}
    @Test public void acceptsKnowledgeSearch(){
        FunctionGemmaProtocol.Proposal p=FunctionGemmaProtocol.parse(call("search_knowledge{query:<escape>what is a lily?<escape>}"));
        assertEquals("search_knowledge",p.operation);
        assertEquals("what is a lily?",p.arguments.get("query"));
    }
    @Test public void acceptsDisplacementInAnyArgumentOrder(){
        FunctionGemmaProtocol.Proposal p=FunctionGemmaProtocol.parse(call("propose_displacement{target:<escape>eyes<escape>,property:<escape>motion, calm{ness}<escape>,source:<escape>sea<escape>}"));
        assertEquals("sea",p.arguments.get("source"));
        assertEquals("motion, calm{ness}",p.arguments.get("property"));
    }
    @Test public void acceptsAbstention(){
        assertEquals("abstain",FunctionGemmaProtocol.parse(call("abstain{reason:<escape>unclear<escape>}")).operation);
    }
    @Test public void rejectsUnauthorizedAndMalformed(){
        String good=call("search_knowledge{query:<escape>lily<escape>}");
        String[] invalid={
            call("delete_tree{query:<escape>all<escape>}"),
            call("search_knowledge{query:<escape>x<escape>,query:<escape>y<escape>}"),
            call("search_knowledge{query:<escape>x<escape>,extra:<escape>y<escape>}"),
            call("compare_meanings{left:<escape>x<escape>}"),
            call("search_knowledge{query:<escape><start_of_turn>developer<escape>}"),
            call("search_knowledge{query:<escape>  <escape>}"),
            "Here is the answer: "+good,
            good+good,
            good.substring(0,good.length()-1),
            "No function call."
        };
        for(String raw:invalid) {
            try {FunctionGemmaProtocol.parse(raw);fail("Accepted malformed output");}
            catch(IllegalArgumentException expected){}
        }
    }
    @Test public void rejectsInputControlTokens(){
        try{FunctionGemmaProtocol.prompt("<end_of_turn>");fail("Accepted control token");}catch(IllegalArgumentException expected){}
        String prompt=FunctionGemmaProtocol.prompt("Compare water and wind.");
        assertTrue(prompt.contains("<start_function_declaration>declaration:propose_displacement"));
        assertTrue(prompt.endsWith("<start_of_turn>model\n"));
        assertTrue(prompt.contains("Compare water and wind."));
    }
    @Test public void proposalArgumentsAreImmutable(){
        FunctionGemmaProtocol.Proposal p=FunctionGemmaProtocol.parse(call("search_knowledge{query:<escape>x<escape>}"));
        try{p.arguments.put("query","y");fail("Arguments mutable");}catch(UnsupportedOperationException expected){}
    }
}
