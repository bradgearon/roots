package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;

public class EncounterArbitrationTest {
    @Test public void rankIsUnsignedAndSymmetric(){
        byte[] low=new byte[8],high=new byte[8];high[0]=(byte)255;
        assertTrue(EncounterArbitration.compareRank(low,high)<0);
        assertTrue(EncounterArbitration.compareRank(high,low)>0);
        assertEquals(0,EncounterArbitration.compareRank(low,low));
        low[0]=(byte)255;low[7]=1;
        assertTrue(EncounterArbitration.compareRank(high,low)<0);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsMalformedRank(){
        EncounterArbitration.compareRank(new byte[7],new byte[8]);
    }
    @Test public void quietAndBlockedPeersBackOff(){
        assertEquals(30000,EncounterArbitration.cooldownMs(5));
        assertEquals(120000,EncounterArbitration.cooldownMs(0));
        assertEquals(600000,EncounterArbitration.cooldownMs(-2));
    }
}
