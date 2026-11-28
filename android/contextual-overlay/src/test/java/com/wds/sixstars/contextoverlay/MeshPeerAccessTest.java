package com.wds.sixstars.contextoverlay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.wds.sixstars.contextoverlay.MeshConnectionDiagnostics.*;

public class MeshPeerAccessTest {
    private static final MeshPeer.Invite A=new MeshPeer.Invite("192.168.1.2","a".repeat(64),"1".repeat(64));
    private static final MeshPeer.Invite B=new MeshPeer.Invite("192.168.1.3","b".repeat(64),"2".repeat(64));
    private static final MeshPeer.Invite CURRENT_A=new MeshPeer.Invite("192.168.1.4",A.pin(),"3".repeat(64));
    private static final String SECRET="roots://mesh/v1?token=DO_NOT_INCLUDE_THIS_SECRET";

    private static AxonSubscription policy(MeshPeer.Invite... peers){
        return AxonSubscription.create(Arrays.asList(peers),IntelligenceModel.BALANCED,ElementFlow.OPERATIONS[0],"*");
    }
    private static final class MemorySource implements MeshPeerAccess.Source {
        AxonSubscription policy=policy(A);
        List<MeshPeer.Invite> saved=new ArrayList<>(List.of(A));
        final Map<String,MeshPeer.Invite> parsed=new HashMap<>();
        int policyReads,peerReads,parses,saves;
        boolean failPolicy,failRead,failSave;
        MemorySource(){parsed.put("a",A);parsed.put("b",B);parsed.put("current-a",CURRENT_A);}
        @Override public AxonSubscription loadPolicy()throws Exception{
            policyReads++;if(failPolicy)throw new IOException(SECRET);return policy;
        }
        @Override public List<MeshPeer.Invite> loadPeers(AxonSubscription policy)throws Exception{
            peerReads++;if(failRead)throw new IOException(SECRET);return saved;
        }
        @Override public void savePeers(AxonSubscription policy,List<MeshPeer.Invite> peers)throws Exception{
            saves++;if(failSave)throw new IOException(SECRET);saved=new ArrayList<>(peers);
        }
        @Override public MeshPeer.Invite parse(String value)throws Exception{
            parses++;MeshPeer.Invite result=parsed.get(value);if(result==null)throw new IOException(SECRET);return result;
        }
    }
    private static Check stage(MeshPeerAccess.Resolution resolution,String name){
        return resolution.checks().stream().filter(c->name.equals(c.stage())).findFirst().orElseThrow();
    }

    @Test public void blankInputReusesBoundSavedAccessWithoutParsingOrResaving(){
        MemorySource source=new MemorySource();
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve(" \n ",true,source);
        assertTrue(result.ready());assertTrue(result.fromSaved());assertSame(source.policy,result.policy());
        assertEquals(List.of(A),result.peers());assertEquals(1,source.policyReads);assertEquals(1,source.peerReads);
        assertEquals(0,source.parses);assertEquals(0,source.saves);assertSame(result,result.requireReady());
        assertEquals(PASS,stage(result,"Saved Axon").state());assertEquals(PASS,stage(result,"Peer access").state());
    }
    @Test public void explicitCurrentInvitationOverridesAndUpdatesSavedAccess(){
        MemorySource source=new MemorySource();
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve("current-a",true,source);
        assertTrue(result.ready());assertFalse(result.fromSaved());assertEquals(List.of(CURRENT_A),result.peers());
        assertEquals(List.of(CURRENT_A),source.saved);assertEquals(1,source.saves);assertEquals(1,source.peerReads);
        assertEquals(List.of(CURRENT_A),MeshPeerAccess.resolve("",true,source).peers());
    }
    @Test public void invalidExplicitInputNeverFallsBackToValidSavedAccess(){
        MemorySource source=new MemorySource();
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve(SECRET,true,source);
        assertFalse(result.ready());assertTrue(result.peers().isEmpty());assertEquals(0,source.peerReads);assertEquals(0,source.saves);
        assertEquals(List.of(A),source.saved);assertFalse(result.checks().toString().contains(SECRET));
        assertFalse(assertThrows(IllegalArgumentException.class,result::requireReady).getMessage().contains(SECRET));
    }
    @Test public void legacyBindingIsReportedBeforeMissingConnectionDetails(){
        MemorySource source=new MemorySource();source.saved=Collections.emptyList();
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve("",true,source);
        assertFalse(result.ready());assertSame(source.policy,result.policy());
        assertEquals("Saved Axon",result.checks().get(0).stage());assertEquals(PASS,result.checks().get(0).state());
        Check access=stage(result,"Peer access");assertEquals(FAIL,access.state());
        assertTrue(access.detail().contains("is bound"));assertTrue(access.nextStep().contains("once"));
        assertTrue(access.nextStep().contains("binding can be kept"));
    }
    @Test public void missingOrBrokenPolicyDoesNotAttemptToReadPeerSecrets(){
        MemorySource missing=new MemorySource();missing.policy=null;
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve("",true,missing);
        assertFalse(result.ready());assertEquals(FAIL,stage(result,"Saved Axon").state());assertEquals(0,missing.peerReads);
        MemorySource broken=new MemorySource();broken.failPolicy=true;
        result=MeshPeerAccess.resolve("a",true,broken);
        assertFalse(result.ready());assertEquals(0,broken.peerReads);assertEquals(0,broken.parses);
        assertFalse(result.checks().toString().contains(SECRET));
    }
    @Test public void unreadableSavedAccessCanBeReplacedByExplicitBoundInvitation(){
        MemorySource source=new MemorySource();source.failRead=true;
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve("",true,source);
        assertFalse(result.ready());assertEquals(PASS,stage(result,"Saved Axon").state());
        assertTrue(stage(result,"Peer access").detail().contains("could not be opened"));assertFalse(result.checks().toString().contains(SECRET));
        result=MeshPeerAccess.resolve("current-a",true,source);
        assertTrue(result.ready());assertEquals(List.of(CURRENT_A),source.saved);assertEquals(2,source.peerReads);
    }
    @Test public void directModeNeverLoadsOrChangesTheSavedBinding(){
        MemorySource source=new MemorySource();
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve("b",false,source);
        assertTrue(result.ready());assertNull(result.policy());assertEquals(List.of(B),result.peers());
        assertEquals(0,source.policyReads);assertEquals(0,source.peerReads);assertEquals(0,source.saves);
        result=MeshPeerAccess.resolve("",false,source);
        assertFalse(result.ready());assertEquals(FAIL,stage(result,"Invitations").state());
    }
    @Test public void enteredUnboundIdentityIsNotSavedOrConnected(){
        MemorySource source=new MemorySource();
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve("b",true,source);
        assertFalse(result.ready());assertTrue(result.peers().isEmpty());assertEquals(FAIL,stage(result,"Axon binding").state());
        assertEquals(0,source.saves);assertEquals(List.of(A),source.saved);
    }
    @Test public void duplicateEndpointAndIdentityAreRejectedBeforeSaving(){
        MemorySource endpoint=new MemorySource();endpoint.policy=policy(A,B);
        endpoint.parsed.put("same-host",new MeshPeer.Invite(A.host(),B.pin(),B.token()));
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve("a\nsame-host",true,endpoint);
        assertFalse(result.ready());assertTrue(stage(result,"Peer access").detail().contains("same endpoint"));assertEquals(0,endpoint.saves);
        MemorySource identity=new MemorySource();
        result=MeshPeerAccess.resolve("a\ncurrent-a",true,identity);
        assertFalse(result.ready());assertTrue(stage(result,"Peer access").detail().contains("same peer identity"));assertEquals(0,identity.saves);
    }
    @Test public void excessiveInputIsRejectedAfterReportingBindingAndBeforeParsing(){
        MemorySource source=new MemorySource();
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve("x".repeat(8193),true,source);
        assertFalse(result.ready());assertEquals(PASS,stage(result,"Saved Axon").state());assertEquals(0,source.parses);
        result=MeshPeerAccess.resolve(String.join("\n",Collections.nCopies(9,"a")),true,source);
        assertFalse(result.ready());assertEquals(0,source.parses);assertEquals(0,source.peerReads);assertEquals(0,source.saves);
    }
    @Test public void incompleteSavedCoverageIsVisibleAndDoesNotInventMissingConnections(){
        MemorySource source=new MemorySource();source.policy=policy(A,B);
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve(null,true,source);
        assertTrue(result.ready());assertEquals(List.of(A),result.peers());
        Check coverage=stage(result,"Peer coverage");assertEquals(WARN,coverage.state());assertTrue(coverage.detail().contains("1 of 2"));
    }
    @Test public void savedGrantsCannotExpandTheSelectedIdentitySet(){
        MemorySource source=new MemorySource();source.saved=new ArrayList<>(List.of(A,B));
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve("",true,source);
        assertTrue(result.ready());assertEquals(List.of(A),result.peers());
    }
    @Test public void saveFailureDoesNotClaimPersistenceOrSupplyUsableGrants(){
        MemorySource source=new MemorySource();source.failSave=true;
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve("current-a",true,source);
        assertFalse(result.ready());assertTrue(result.peers().isEmpty());assertEquals(List.of(A),source.saved);
        assertEquals(FAIL,stage(result,"Saved peer access").state());assertFalse(result.checks().toString().contains(SECRET));
        assertFalse(result.checks().stream().anyMatch(c->PASS.equals(c.state())&&c.detail().contains("saved encrypted")));
    }
    @Test public void partialExplicitSelectionDoesNotSilentlyAddPreviouslySavedPeers(){
        MemorySource source=new MemorySource();source.policy=policy(A,B);source.saved=new ArrayList<>(List.of(A,B));
        MeshPeerAccess.Resolution result=MeshPeerAccess.resolve("current-a",true,source);
        assertTrue(result.ready());assertEquals(List.of(CURRENT_A),result.peers());assertEquals(1,source.peerReads);
        assertEquals(List.of(CURRENT_A,B),source.saved);
        assertEquals(List.of(CURRENT_A,B),MeshPeerAccess.resolve("",true,source).peers());
        assertEquals(WARN,stage(result,"Peer coverage").state());
    }
}
