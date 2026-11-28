package com.wds.sixstars.contextoverlay;
import org.junit.Test;
import static org.junit.Assert.*;
public class EncounterReceivePolicyTest {
    @Test public void nearbyBindingDoesNotNeedLanSubscription(){
        assertTrue(EncounterReceivePolicy.nearby(true,false,1,true));
        assertTrue(EncounterReceivePolicy.nearby(true,true,1,true));
    }
    @Test public void discoverBeforeBinding(){
        assertTrue(EncounterReceivePolicy.nearby(true,false,0,true));
        assertFalse(EncounterReceivePolicy.nearby(true,true,0,true));
        assertFalse(EncounterReceivePolicy.nearby(true,false,0,false));
    }
    @Test public void explicitLanDraftIsAuthoritative(){
        assertFalse(EncounterReceivePolicy.nearby(false,false,5,true));
    }
}
