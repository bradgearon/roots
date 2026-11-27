package com.wds.sixstars.contextoverlay;

import com.samskivert.mustache.MustacheException;
import com.samskivert.mustache.Template;
import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MeshDiagnosticTemplatesTest {
    private static Template template(String name)throws IOException{
        for(Path dir:List.of(Path.of("src/main/assets/templates"),Path.of("contextual-overlay/src/main/assets/templates"),
                Path.of("android/contextual-overlay/src/main/assets/templates"))){
            Path file=dir.resolve(name+".mustache");
            if(Files.isRegularFile(file))try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){
                return TextTemplates.compile(reader);
            }
        }
        throw new IOException("Run template checks from the repository or Android module directory.");
    }
    private static MeshConnectionDiagnostics.Check check(String stage,String state,String detail,String next){
        return new MeshConnectionDiagnostics.Check(stage,state,detail,next,17);
    }
    private static MeshConnectionDiagnostics.Report report(List<MeshConnectionDiagnostics.Check> local,List<MeshConnectionDiagnostics.PeerReport> peers){
        return new MeshConnectionDiagnostics.Report(1700000000000L,local,peers);
    }

    @Test public void rendersRealMustacheSectionsAndPlainText(){
        Template template=TextTemplates.compile(new StringReader("{{#items}}[{{name}}]{{/items}}{{^items}}none{{/items}}"));
        assertEquals("[A&B][*]",template.execute(Map.of("items",List.of(Map.of("name","A&B"),Map.of("name","*")))));
        assertEquals("none",template.execute(Map.of("items",List.of())));
        Template optional=TextTemplates.compile(new StringReader("{{#value}}Next: {{.}}{{/value}}"));
        assertEquals("",optional.execute(Map.of("value","")));
    }

    @Test public void missingTemplateFieldsFailClearly(){
        assertThrows(MustacheException.class,()->TextTemplates.compile(new StringReader("{{missing}}")).execute(Map.of()));
        assertThrows(MustacheException.class,()->TextTemplates.compile(new StringReader("{{#missing}}hidden{{/missing}}")).execute(Map.of()));
    }

    @Test public void viewShowsMeasuredStagesTimingsAndNextSteps()throws Exception{
        MeshConnectionDiagnostics.Report report=report(List.of(check("Wi-Fi","PASS","192.168.1.9/24","")),List.of(
            new MeshConnectionDiagnostics.PeerReport("192.168.1.3:8233","abc123456789",List.of(
                check("TCP","PASS","Socket connected.",""),
                check("TLS","FAIL","Certificate did not match.","Copy a new invitation."),
                check("Inventory","SKIP","Not attempted.","")))));
        String rendered=MeshDiagnosticTemplates.report(template("mesh_connection_report"),report);
        assertTrue(rendered.contains("Peer 1 · 192.168.1.3:8233"));
        assertTrue(rendered.contains("Identity: abc123456789"));
        assertTrue(rendered.contains("[FAIL] TLS · 17 ms"));
        assertTrue(rendered.contains("Next: Copy a new invitation."));
        assertTrue(rendered.contains("[SKIP] Inventory"));
        assertFalse(rendered.contains("{{"));
    }

    @Test public void promptRetainsEveryPeerAndFailureWithinModelBudget()throws Exception{
        List<MeshConnectionDiagnostics.PeerReport> peers=new ArrayList<>();
        String longDetail="The peer closed without confirming access. ".repeat(12);
        for(int i=1;i<=8;i++)peers.add(new MeshConnectionDiagnostics.PeerReport("192.168.1."+i+":8233","shortpin",List.of(
            check("Invitation","PASS","Valid invitation.",""),check("Subnet","PASS","Same subnet.",""),
            check("TCP","PASS","Connected.",""),check("TLS","PASS","Verified.",""),
            check("Authentication","FAIL",longDetail,"Copy a current invitation from the peer."),
            check("Inventory","SKIP","Not tested.",""))));
        String request=MeshDiagnosticTemplates.analysis(template("mesh_connection_analysis"),report(
            List.of(check("Wi-Fi","PASS","Current Wi-Fi ready.",""),check("Native Tree","WARN","Unavailable.","Rebuild the native Tree.")),peers));
        assertTrue(request.length()<=MeshDiagnosticTemplates.MAX_REQUEST_CHARS);
        assertTrue(request.length()<3000);
        for(int i=1;i<=8;i++)assertTrue(request.contains("Peer "+i+" 192.168.1."+i+":8233"));
        assertEquals(8,request.split("Authentication FAIL",-1).length-1);
        assertTrue(request.contains("Native Tree WARN"));
        assertTrue(request.contains("1 not tested"));
        assertFalse(request.contains("<"));
    }

    @Test public void successfulEmptyInventoryRemainsEvidenceInPrompt()throws Exception{
        MeshConnectionDiagnostics.Report report=report(List.of(check("Wi-Fi","PASS","192.168.1.9/24","")),List.of(new MeshConnectionDiagnostics.PeerReport("192.168.1.4:8233","shortpin",List.of(
            check("TLS","PASS","Verified.",""),check("Inventory","PASS","0 published items. The peer is reachable but has no published knowledge.","")))));
        String request=MeshDiagnosticTemplates.analysis(template("mesh_connection_analysis"),report);
        assertTrue(request.contains("Inventory PASS: 0 published items"));
        assertTrue(request.contains("Wi-Fi PASS: 192.168.1.9/24"));
        assertTrue(request.contains("2 passed, 0 not tested"));
        assertFalse(request.contains("FAIL"));
        assertFalse(request.contains(" Next: "));
    }

    @Test public void secretsAreRedactedBeforeTruncationAndNeverBecomePromptMarkup()throws Exception{
        String secret="0123456789abcdef".repeat(4);
        String invitation="roots://mesh/v1?host=192.168.1.3&port=8233&pin="+secret+"&token="+secret;
        String detail="Access error "+invitation+" token="+secret+" raw "+secret+" <special>";
        MeshConnectionDiagnostics.Report report=report(List.of(check("Input","FAIL",detail,"Paste a fresh invitation.")),List.of());
        String view=MeshDiagnosticTemplates.report(template("mesh_connection_report"),report);
        String request=MeshDiagnosticTemplates.analysis(template("mesh_connection_analysis"),report);
        for(String result:List.of(view,request)){
            assertFalse(result.contains("roots://"));
            assertFalse(result.contains(secret));
            assertFalse(result.contains(secret.substring(0,20)));
            assertFalse(result.contains("<"));
            assertTrue(result.contains("redacted"));
        }
    }

    @Test public void ambiguousEofAndEmptyTestAreNotPresentedAsConfirmedAuthenticationFailures()throws Exception{
        MeshConnectionDiagnostics.Report report=report(List.of(check("Invitation","FAIL","Enter one to eight invitations.","Paste an invitation.")),List.of());
        String view=MeshDiagnosticTemplates.report(template("mesh_connection_report"),report);
        String request=MeshDiagnosticTemplates.analysis(template("mesh_connection_analysis"),report);
        assertTrue(view.contains("No peer checks were run."));
        assertTrue(request.contains("EOF alone does not prove a stale token"));
        assertTrue(request.contains("never claim an untested stage passed"));
        assertTrue(request.contains("Runtime arming is not needed for manual receive"));
        assertTrue(request.contains("A local listener is optional for receiving"));
        assertTrue(request.contains("Native Tree affects knowledge storage, not transport tests"));
        assertTrue(request.contains("If no connection failure was measured, say so"));
    }

    @Test public void realisticEightPeerPromptKeepsAuthenticationRejectionDistinctFromUnconfirmedAccess()throws Exception{
        List<MeshConnectionDiagnostics.PeerReport> peers=new ArrayList<>();
        MeshConnectionDiagnostics.Check rejected=MeshConnectionDiagnostics.failure("Authentication",MeshConnectionDiagnostics.protocolFailure("unauthorized"),8);
        MeshConnectionDiagnostics.Check unconfirmed=MeshConnectionDiagnostics.failure("Authentication",new EOFException(),8);
        for(int i=1;i<=8;i++)peers.add(new MeshConnectionDiagnostics.PeerReport("192.168.1."+(20+i)+":8233","shortpin",List.of(
            check("Invitation","PASS","Valid.",""),check("Axon binding","PASS","Bound.",""),
            check("Subnet","PASS","Same subnet.",""),check("TCP","PASS","Connected.",""),check("TLS","PASS","Verified.",""),
            check("Protocol",i%2==0?"PASS":"SKIP","", ""),i%2==0?rejected:unconfirmed,check("Inventory","SKIP","Not tested.",""))));
        List<MeshConnectionDiagnostics.Check> local=List.of(check("Wi-Fi","PASS","Current Wi-Fi ready.",""),
            check("Local IPv4","PASS","192.168.1.9/24",""),check("Local listener","SKIP","Optional.",""),
            check("Native Tree","WARN","Local knowledge storage is unavailable. The network test can still run.","Install a build with Native Tree before receiving knowledge."),
            check("Runtime Axon","SKIP","Optional.",""),check("Saved Axon","PASS","Policy loaded.",""));
        String request=MeshDiagnosticTemplates.analysis(template("mesh_connection_analysis"),report(local,peers));
        assertTrue(request.length()<=MeshDiagnosticTemplates.MAX_REQUEST_CHARS);
        assertTrue(request.contains(rejected.detail()));
        assertTrue(request.contains(unconfirmed.detail()));
        assertEquals(8,request.split("Authentication FAIL",-1).length-1);
        assertFalse(request.contains("Passed: ."));
    }

    @Test public void budgetSkippedPeerKeepsReasonAndRetryActionWithoutBecomingFailure()throws Exception{
        MeshConnectionDiagnostics.Report report=report(List.of(
            check("Runtime Axon","SKIP","Runtime is not armed; optional for this operation.","")),List.of(
            new MeshConnectionDiagnostics.PeerReport("192.168.1.28:8233","shortpin",List.of(
                check("Invitation","PASS","Parsed.",""),
                check("Subnet","SKIP","The 30-second test budget was used by earlier peers.","Test this peer separately."),
                check("TCP","SKIP","Not attempted.",""),check("TLS","SKIP","Not attempted.","")))));
        String request=MeshDiagnosticTemplates.analysis(template("mesh_connection_analysis"),report);
        assertTrue(request.contains("1 passed, 3 not tested"));
        assertTrue(request.contains("Subnet SKIP: The 30-second test budget was used by earlier peers."));
        assertTrue(request.contains("Next: Test this peer separately."));
        assertTrue(request.contains("SKIP is not a failure"));
        assertFalse(request.contains("Runtime Axon SKIP"));
        assertFalse(request.contains("FAIL"));
    }
}
