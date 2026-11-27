package com.wds.sixstars.contextoverlay;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;

public final class DesktopIntelligenceProtocolTest {
    private static JSONObject model()throws Exception{
        return new JSONObject().put("version",1).put("model",IntelligenceModel.BALANCED.name()).put("modelSha",IntelligenceModel.BALANCED.sha);
    }
    private interface Checked {void run()throws Exception;}
    private static void rejects(Checked action)throws Exception{
        try{action.run();fail("Expected validation failure");}
        catch(DesktopIntelligenceProtocol.Failure|IllegalArgumentException expected){}
    }
    @Test public void endpointUsesOnlyDedicatedHttpsBase()throws Exception{
        assertEquals("https://pc.local:8221",DesktopIntelligenceProtocol.baseUrl(" https://PC.local:8221/ "));
        assertEquals("https://192.168.1.223:8221",DesktopIntelligenceProtocol.baseUrl("https://192.168.1.223:8221"));
        for(String value:new String[]{"http://pc:8221","https://pc:8223","https://pc:8081","https://pc", "https://token@pc:8221",
            "https://pc:8221/api/translation/v1", "https://pc:8221/?token=secret", "https://pc:8221/#secret"})
            rejects(()->DesktopIntelligenceProtocol.baseUrl(value));
    }
    @Test public void endpointsCannotTargetThisPhoneOrMulticast()throws Exception{
        for(String host:new String[]{"localhost","127.0.0.1","127.1","0.0.0.0","[::1]","[0:0:0:0:0:0:0:1]","[::ffff:127.0.0.1]","224.0.0.1"})
            rejects(()->DesktopIntelligenceProtocol.baseUrl("https://"+host+":8221"));
    }
    @Test public void keyRequiresIndependentTokenAndExactCertificatePin()throws Exception{
        String pair="a".repeat(64)+":"+"b".repeat(64);
        assertEquals(pair,DesktopIntelligenceProtocol.pairedKey(pair.toUpperCase()));
        for(String invalid:new String[]{"a".repeat(64),pair+":extra","a".repeat(63)+":"+"b".repeat(64),"z".repeat(64)+":"+"b".repeat(64)})
            rejects(()->DesktopIntelligenceProtocol.pairedKey(invalid));
    }
    @Test public void requestsCarryExactModelIdentityAndBoundPrompt()throws Exception{
        JSONObject request=DesktopIntelligenceProtocol.request(IntelligenceModel.BALANCED,"Explain the connection.");
        assertEquals(1,request.getInt("version"));assertEquals("BALANCED",request.getString("model"));
        assertEquals(IntelligenceModel.BALANCED.sha,request.getString("modelSha"));assertEquals(384,request.getInt("maxTokens"));
        assertEquals("Explain the connection.",request.getString("prompt"));assertFalse(request.has("token"));
        DesktopIntelligenceProtocol.request(IntelligenceModel.BALANCED,"a".repeat(3000));
        rejects(()->DesktopIntelligenceProtocol.request(IntelligenceModel.BALANCED,"a".repeat(3001)));
        rejects(()->DesktopIntelligenceProtocol.request(IntelligenceModel.BALANCED,"  "));
        rejects(()->DesktopIntelligenceProtocol.request(IntelligenceModel.BALANCED,"<role>"));
    }
    @Test public void healthRequiresIntelligenceCapabilityAndSelectedModel()throws Exception{
        JSONObject health=model().put("ok",true).put("capability","intelligence");
        DesktopIntelligenceProtocol.health(health,IntelligenceModel.BALANCED);
        rejects(()->DesktopIntelligenceProtocol.health(health,IntelligenceModel.ALTERNATE));
        rejects(()->DesktopIntelligenceProtocol.health(model().put("ok",true).put("capability","translation"),IntelligenceModel.BALANCED));
        rejects(()->DesktopIntelligenceProtocol.health(model().put("ok",false).put("capability","intelligence"),IntelligenceModel.BALANCED));
        rejects(()->DesktopIntelligenceProtocol.health(health.put("modelSha","c".repeat(64)),IntelligenceModel.BALANCED));
    }
    @Test public void maximumAxonReferenceContextFitsDesktopPromptBudget()throws Exception{
        String request="r".repeat(1500),context="c".repeat(RuntimeNeed.MAX_CONTEXT_CHARS);
        String augmented=RuntimeNeed.augment(request,context);
        assertTrue(augmented.length()<=DesktopIntelligenceProtocol.MAX_PROMPT);
        assertEquals(augmented,DesktopIntelligenceProtocol.request(IntelligenceModel.BALANCED,augmented).getString("prompt"));
    }
    @Test public void outputCannotSilentlySubstituteModelsOrTranslation()throws Exception{
        assertEquals("Hello",DesktopIntelligenceProtocol.answer(model().put("text"," Hello "),IntelligenceModel.BALANCED));
        rejects(()->DesktopIntelligenceProtocol.answer(model().put("version",2).put("text","Hello"),IntelligenceModel.BALANCED));
        rejects(()->DesktopIntelligenceProtocol.answer(new JSONObject().put("translation","Hello"),IntelligenceModel.BALANCED));
        rejects(()->DesktopIntelligenceProtocol.answer(model().put("text","Hello"),IntelligenceModel.TINY));
        rejects(()->DesktopIntelligenceProtocol.answer(model().put("text",42),IntelligenceModel.BALANCED));
        rejects(()->DesktopIntelligenceProtocol.answer(model().put("text"," "),IntelligenceModel.BALANCED));
        rejects(()->DesktopIntelligenceProtocol.answer(model().put("text","a".repeat(16001)),IntelligenceModel.BALANCED));
    }
    @Test public void responseReaderRejectsExcessData()throws Exception{
        String text="{\"text\":\"Bonjour\"}";
        assertEquals(text,DesktopIntelligenceProtocol.readLimited(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        rejects(()->DesktopIntelligenceProtocol.readLimited(new ByteArrayInputStream(new byte[DesktopIntelligenceProtocol.MAX_RESPONSE+1])));
    }
    @Test public void failureMessagesIdentifyIntelligenceRecovery()throws Exception{
        assertTrue(DesktopIntelligenceProtocol.httpFailure(401).getMessage().contains("get-intelligence-key.cmd"));
        assertTrue(DesktopIntelligenceProtocol.httpFailure(409).getMessage().contains("model"));
        assertTrue(DesktopIntelligenceProtocol.httpFailure(503).getMessage().contains("launch-intelligence.cmd"));
        assertTrue(DesktopIntelligenceProtocol.httpFailure(302).getMessage().contains("HTTP 302"));
    }
}
