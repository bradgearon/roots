package com.wds.sixstars.contextoverlay;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AxonPeerStoreTest {
    private static MeshPeer.Invite peer(int number){
        return new MeshPeer.Invite("192.168.1."+number,String.format("%064x",number),String.format("%064x",100+number));
    }
    private static AxonSubscription policy(List<MeshPeer.Invite> peers){
        return AxonSubscription.create(peers,IntelligenceModel.BALANCED,ElementFlow.OPERATIONS[0],"music\n*");
    }
    private static SecretKey key()throws Exception{
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);return generator.generateKey();
    }
    private static byte[] bytes(JSONObject json){return json.toString().getBytes(StandardCharsets.UTF_8);}
    private static void safeFailure(Throwable failure,MeshPeer.Invite peer){
        assertNull("Do not expose parser/provider causes to UI callers",failure.getCause());
        assertFalse(failure.toString().contains(peer.pin()));assertFalse(failure.toString().contains(peer.token()));
    }

    @Test public void encryptedRoundTripRestoresAllBoundTargetsWithoutPlaintextCredentials()throws Exception{
        List<MeshPeer.Invite> peers=new ArrayList<>();for(int i=1;i<=8;i++)peers.add(peer(i));
        AxonSubscription policy=policy(peers);SecretKey key=key();
        byte[] plain=AxonPeerStore.encode(peers,policy);
        byte[] first=AxonPeerStore.encrypt(plain,key,policy),second=AxonPeerStore.encrypt(plain,key,policy);
        assertFalse("Each save needs a fresh nonce",Arrays.equals(first,second));
        for(MeshPeer.Invite peer:peers){
            String persisted=new String(first,StandardCharsets.ISO_8859_1);
            assertFalse(persisted.contains(peer.host()));assertFalse(persisted.contains(peer.pin()));assertFalse(persisted.contains(peer.token()));
        }
        List<MeshPeer.Invite> restored=AxonPeerStore.decode(AxonPeerStore.decrypt(first,key,policy),policy);
        assertEquals(peers,restored);
        assertThrows(UnsupportedOperationException.class,()->restored.add(peer(9)));
    }

    @Test public void modifiedNoncePayloadTagAndHeaderCannotRestoreAnyPeer()throws Exception{
        MeshPeer.Invite peer=peer(1);AxonSubscription policy=policy(List.of(peer));SecretKey key=key();
        byte[] sealed=AxonPeerStore.encrypt(AxonPeerStore.encode(List.of(peer),policy),key,policy);
        for(int index:new int[]{0,4,20,sealed.length-1}){
            byte[] modified=sealed.clone();modified[index]^=1;
            safeFailure(assertThrows(IOException.class,()->AxonPeerStore.decrypt(modified,key,policy)),peer);
        }
        for(byte[] modified:List.of(Arrays.copyOf(sealed,14),Arrays.copyOf(sealed,sealed.length-1),Arrays.copyOf(sealed,sealed.length+1)))
            safeFailure(assertThrows(IOException.class,()->AxonPeerStore.decrypt(modified,key,policy)),peer);
        SecretKey other=key();
        safeFailure(assertThrows(IOException.class,()->AxonPeerStore.decrypt(sealed,other,policy)),peer);
    }

    @Test public void savedAccessCannotBeMovedToAnotherBindingButSettingsCanChange()throws Exception{
        MeshPeer.Invite one=peer(1),two=peer(2);AxonSubscription original=policy(List.of(one));SecretKey key=key();
        byte[] sealed=AxonPeerStore.encrypt(AxonPeerStore.encode(List.of(one),original),key,original);
        AxonSubscription other=policy(List.of(two)),expanded=policy(List.of(one,two));
        assertThrows(IOException.class,()->AxonPeerStore.decrypt(sealed,key,other));
        assertThrows(IOException.class,()->AxonPeerStore.decrypt(sealed,key,expanded));
        AxonSubscription edited=original.withSettings(IntelligenceModel.TINY,ElementFlow.OPERATIONS[1],"poetry | 1:2\n*");
        assertEquals(original.pins,edited.pins);
        assertEquals(List.of(one),AxonPeerStore.decode(AxonPeerStore.decrypt(sealed,key,edited),edited));
        assertEquals("poetry | 1:2\n*",edited.lines());
    }

    @Test public void changedAddressAndTokenForSameIdentityCanReplaceOldGrant()throws Exception{
        MeshPeer.Invite original=peer(1);AxonSubscription policy=policy(List.of(original));
        MeshPeer.Invite current=new MeshPeer.Invite("192.168.1.25",original.pin(),"e".repeat(64));
        byte[] encoded=AxonPeerStore.encode(List.of(current),policy);
        List<MeshPeer.Invite> restored=AxonPeerStore.decode(encoded,policy);
        assertEquals(List.of(current),restored);assertFalse(restored.contains(original));
        assertFalse(new String(encoded,StandardCharsets.UTF_8).contains(original.token()));
    }

    @Test public void unrelatedMalformedAndConflictingEntriesFailClosed()throws Exception{
        MeshPeer.Invite one=peer(1),two=peer(2);AxonSubscription policy=policy(List.of(one,two));
        for(List<MeshPeer.Invite> peers:List.of(
            List.<MeshPeer.Invite>of(),List.of(peer(3)),List.of(one,one),
            List.of(one,new MeshPeer.Invite(one.host(),two.pin(),two.token())),
            List.of(new MeshPeer.Invite("192.168.1.300",one.pin(),one.token())),
            List.of(new MeshPeer.Invite("host.example",one.pin(),one.token())),
            List.of(new MeshPeer.Invite(one.host(),one.pin(),"bad-token"))))
            safeFailure(assertThrows(IOException.class,()->AxonPeerStore.encode(peers,policy)),one);
        JSONObject json=new JSONObject(new String(AxonPeerStore.encode(List.of(one),policy),StandardCharsets.UTF_8));
        json.getJSONArray("peers").getJSONObject(0).put("pin",peer(3).pin());
        safeFailure(assertThrows(IOException.class,()->AxonPeerStore.decode(bytes(json),policy)),one);
        assertThrows(IOException.class,()->AxonPeerStore.decode(("invalid JSON "+one.token()).getBytes(StandardCharsets.UTF_8),policy));
        assertThrows(IOException.class,()->AxonPeerStore.decode("{}".getBytes(StandardCharsets.UTF_8),policy));
    }

    @Test public void inputBoundsRejectOversizedFilesAndTooManyPeers()throws Exception{
        AxonSubscription policy=policy(List.of(peer(1)));byte[] oversized=new byte[20000];
        assertThrows(IOException.class,()->AxonPeerStore.readBounded(new ByteArrayInputStream(oversized)));
        assertThrows(IOException.class,()->AxonPeerStore.decode(oversized,policy));
        assertThrows(IOException.class,()->AxonPeerStore.decrypt(oversized,key(),policy));
        JSONObject json=new JSONObject(new String(AxonPeerStore.encode(List.of(peer(1)),policy),StandardCharsets.UTF_8));
        JSONArray rows=json.getJSONArray("peers");JSONObject row=rows.getJSONObject(0);
        for(int i=1;i<9;i++)rows.put(row);
        assertThrows(IOException.class,()->AxonPeerStore.decode(bytes(json),policy));
        byte[] encoded=AxonPeerStore.encode(List.of(peer(1)),policy);
        assertArrayEquals(encoded,AxonPeerStore.readBounded(new ByteArrayInputStream(encoded)));
    }
}
