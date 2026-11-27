package com.wds.sixstars.contextoverlay;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.wds.sixstars.contextoverlay.MeshConnectionDiagnostics.*;

public class MeshConnectionDiagnosticsTest {
    private static final String SECRET="roots://mesh/v1?host=192.168.1.2&token=SECRET_TOKEN_AND_PRIVATE_CONTENT";
    @Test public void classifiesTcpFailuresWithoutExceptionMessages(){
        Check timeout=failure("TCP",new SocketTimeoutException(SECRET),7);
        assertTrue(timeout.detail().contains("timed out"));assertEquals(7,timeout.elapsedMs());
        assertFalse(timeout.toString().contains(SECRET));
        assertTrue(failure("TCP",new ConnectException(SECRET),0).detail().contains("rejected"));
        assertTrue(failure("TCP",new NoRouteToHostException(SECRET),0).detail().contains("No network route"));
        assertTrue(failure("TCP",new SecurityException(SECRET),0).detail().contains("Android denied"));
    }
    @Test public void distinguishesCertificateDatesFromIdentityFailure(){
        SSLHandshakeException handshake=new SSLHandshakeException(SECRET);
        handshake.initCause(new CertificateExpiredException(SECRET));
        assertTrue(failure("TLS",handshake,0).detail().contains("expired"));
        assertTrue(failure("TLS",new CertificateNotYetValidException(SECRET),0).detail().contains("not yet valid"));
        Check pin=failure("TLS",new CertificateException(SECRET),0);
        assertTrue(pin.detail().contains("invitation identity"));assertFalse(pin.toString().contains(SECRET));
    }
    @Test public void olderPeerClosureDoesNotClaimAuthenticationRejection(){
        Check eof=failure("Authentication",new EOFException(SECRET),10);
        assertTrue(eof.detail().startsWith("Access unconfirmed"));
        assertFalse(eof.detail().contains("rejected"));
        Check rejected=protocolFailure("unauthorized").check;
        assertEquals("Authentication",rejected.stage());assertTrue(rejected.detail().contains("rejected"));
    }
    @Test public void unknownPeerErrorIsNotCopiedIntoReport(){
        Failure unknown=protocolFailure(SECRET);
        assertEquals("Protocol",unknown.check.stage());
        assertFalse(unknown.getMessage().contains(SECRET));assertFalse(unknown.check.toString().contains(SECRET));
        assertEquals("Protocol",protocolFailure("unsupported_version").check.stage());
        assertEquals("Protocol",protocolFailure("unsupported_operation").check.stage());
        assertEquals("Protocol",protocolFailure("invalid_request").check.stage());
        assertEquals("Receive",protocolFailure("unavailable").check.stage());
    }
    @Test public void reportCopiesAllListsAndExposesImmutableViews(){
        List<Check> checks=new ArrayList<>();checks.add(check("TCP",PASS,"Connected",""));
        PeerReport peer=new PeerReport("192.168.1.2:8233","0123456789ab…",checks);
        List<PeerReport> peers=new ArrayList<>();peers.add(peer);
        List<Check> local=new ArrayList<>();local.add(check("Wi-Fi",PASS,"Ready",""));
        Report report=new Report(123,local,peers);
        checks.clear();peers.clear();local.clear();
        assertEquals(1,report.localChecks().size());assertEquals(1,report.peers().size());assertEquals(1,peer.checks().size());
        assertThrows(UnsupportedOperationException.class,()->report.peers().clear());
        assertThrows(UnsupportedOperationException.class,()->report.localChecks().clear());
        assertThrows(UnsupportedOperationException.class,()->peer.checks().clear());
    }
    @Test public void failureKeepsEarlierPassAndSkipsLaterStages(){
        Trace trace=new Trace("192.168.1.2:8233","0123456789ab…",null);trace.prepare();
        trace.begin("Subnet",10);trace.pass("Subnet","Same subnet",12);
        trace.begin("TCP",12);trace.fail(new ConnectException(SECRET),30);
        assertEquals(PASS,stage(trace,"Subnet").state());assertEquals(FAIL,stage(trace,"TCP").state());
        assertEquals(18,stage(trace,"TCP").elapsedMs());
        for(String stage:Arrays.asList("TLS","Protocol","Authentication","Inventory"))assertEquals(SKIP,stage(trace,stage).state());
        assertFalse(trace.snapshot().toString().contains(SECRET));
    }
    @Test public void recoveredAttemptDoesNotEraseEarlierFailure(){
        Trace trace=new Trace("192.168.1.2:8233","0123456789ab…",null);trace.prepare();
        trace.begin("TCP",10);trace.fail(new ConnectException(SECRET),30);
        trace.begin("TCP",40);trace.pass("TCP","Connected",45);
        Check recovered=stage(trace,"TCP");assertEquals(WARN,recovered.state());assertTrue(recovered.detail().contains("rejected"));
        trace.begin("TCP",50);trace.pass("TCP","Connected again",55);
        assertEquals(recovered.detail(),stage(trace,"TCP").detail());assertEquals(WARN,stage(trace,"TCP").state());
    }
    @Test public void protocolConfirmationDoesNotDoubleChargeAuthenticationLatency(){
        Trace trace=new Trace("192.168.1.2:8233","0123456789ab…",null);trace.prepare();
        trace.begin("Authentication",100);trace.pass("Protocol","Mesh v1",120);trace.pass("Authentication","Accepted",120);
        assertEquals(0,stage(trace,"Protocol").elapsedMs());assertEquals(20,stage(trace,"Authentication").elapsedMs());
    }
    @Test public void explicitProtocolFailureRecordsItsOwnStage(){
        Trace trace=new Trace("192.168.1.2:8233","0123456789ab…",null);trace.prepare();
        trace.begin("Authentication",100);trace.fail(protocolFailure("unsupported_version"),120);
        assertEquals(FAIL,stage(trace,"Protocol").state());assertEquals(SKIP,stage(trace,"Authentication").state());
    }
    @Test public void summaryIdentifiesEndpointAndStage(){
        PeerReport peer=new PeerReport("192.168.1.2:8233","0123456789ab…",Collections.singletonList(protocolFailure("unauthorized").check));
        String summary=failureSummary(new Report(123,Collections.emptyList(),Collections.singletonList(peer)));
        assertTrue(summary.contains("192.168.1.2:8233"));assertTrue(summary.contains("Authentication"));
    }
    @Test public void tlsCompatibilityRetryNeverRetriesCertificateFailure(){
        assertTrue(canRetryTls12(new SSLHandshakeException("handshake_failure")));
        SSLHandshakeException badPin=new SSLHandshakeException("certificate rejected");badPin.initCause(new CertificateException(SECRET));
        assertFalse(canRetryTls12(badPin));
        assertFalse(canRetryTls12(new SocketTimeoutException(SECRET)));
        Check detail=failure("TLS",new SSLHandshakeException("no suitable certificate "+SECRET),0);
        assertTrue(detail.detail().contains("could not select a server certificate"));
        assertFalse(detail.toString().contains(SECRET));
    }
    @Test public void onlyAuthenticatedInventoryCountsAsLiveConnection(){
        PeerReport tcpOnly=new PeerReport("a","a",List.of(check("TCP",PASS,"",""),check("TLS",FAIL,"","")));
        PeerReport connected=new PeerReport("b","b",List.of(check("TLS",PASS,"",""),check("Authentication",PASS,"",""),check("Inventory",PASS,"","")));
        assertEquals(1,connectedPeers(new Report(1,List.of(),List.of(tcpOnly,connected))));
    }
    private static Check stage(Trace trace,String stage){return trace.snapshot().checks().stream().filter(c->c.stage().equals(stage)).findFirst().orElseThrow();}
}
