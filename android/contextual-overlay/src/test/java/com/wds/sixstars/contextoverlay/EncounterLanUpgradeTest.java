package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class EncounterLanUpgradeTest {
    private static byte[] address(int a,int b,int c,int d){return new byte[]{(byte)a,(byte)b,(byte)c,(byte)d};}
    @Test public void allowsOnlyDifferentHostsOnSameSubnet(){
        byte[] local=address(192,168,1,20);
        assertTrue(EncounterLanUpgrade.sameSubnet(local,address(192,168,1,21),24));
        assertFalse(EncounterLanUpgrade.sameSubnet(local,local,24));
        assertFalse(EncounterLanUpgrade.sameSubnet(local,address(192,168,2,20),24));
        assertFalse(EncounterLanUpgrade.sameSubnet(local,address(192,168,1,255),24));
        assertFalse(EncounterLanUpgrade.sameSubnet(local,address(192,168,1,0),24));
        assertFalse(EncounterLanUpgrade.sameSubnet(local,address(192,168,1,21),0));
    }
    @Test public void supportsNonOctetPrefixes(){
        byte[] local=address(10,0,0,17);
        assertTrue(EncounterLanUpgrade.sameSubnet(local,address(10,0,0,18),28));
        assertFalse(EncounterLanUpgrade.sameSubnet(local,address(10,0,0,33),28));
        assertFalse(EncounterLanUpgrade.sameSubnet(local,address(10,0,0,31),28));
    }
}
