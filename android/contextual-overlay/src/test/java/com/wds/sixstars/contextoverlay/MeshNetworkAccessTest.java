package com.wds.sixstars.contextoverlay;

import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeshNetworkAccessTest {
    private static final String SOURCE="a".repeat(64),MEMBER="b".repeat(64),OTHER="c".repeat(64);
    @Test public void grantsAreStableAcrossReloadOfTheSameRoot()throws Exception{
        byte[] root=new byte[32];java.util.Arrays.fill(root,(byte)7);
        SecretKeySpec first=new SecretKeySpec(root,"HmacSHA256");
        SecretKeySpec reopened=new SecretKeySpec(root.clone(),"HmacSHA256");
        assertEquals(MeshNetworkAccess.enrollment(first,SOURCE),MeshNetworkAccess.enrollment(reopened,SOURCE));
        assertEquals(MeshNetworkAccess.member(first,SOURCE,MEMBER),MeshNetworkAccess.member(reopened,SOURCE,MEMBER));
        assertTrue(MeshNetworkAccess.validHex(MeshNetworkAccess.member(first,SOURCE,MEMBER)));
    }
    @Test public void eachMemberAndIssuerHasADifferentGrant()throws Exception{
        SecretKeySpec root=new SecretKeySpec(new byte[32],"HmacSHA256");
        String token=MeshNetworkAccess.member(root,SOURCE,MEMBER);
        assertNotEquals(token,MeshNetworkAccess.member(root,SOURCE,OTHER));
        assertNotEquals(token,MeshNetworkAccess.member(root,OTHER,MEMBER));
        assertNotEquals(token,MeshNetworkAccess.enrollment(root,SOURCE));
        assertFalse(MeshNetworkAccess.equal(token,MeshNetworkAccess.member(root,SOURCE,OTHER)));
        assertTrue(MeshNetworkAccess.equal(token,token));
        assertFalse(MeshNetworkAccess.equal(token,""));
    }
    @Test public void anotherRootCannotGenerateTheSameGrant()throws Exception{
        byte[] other=new byte[32];other[0]=1;
        assertNotEquals(MeshNetworkAccess.member(new SecretKeySpec(new byte[32],"HmacSHA256"),SOURCE,MEMBER),
            MeshNetworkAccess.member(new SecretKeySpec(other,"HmacSHA256"),SOURCE,MEMBER));
    }
}
