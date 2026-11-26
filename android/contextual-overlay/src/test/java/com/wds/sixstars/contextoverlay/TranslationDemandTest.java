package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationDemandTest {
    @Test public void reversalAndIdleAreImmediateAndBounded(){
        TranslationDemand d=new TranslationDemand();
        d.snapshot(1000);
        d.measuredScroll(80,100);
        assertTrue(d.velocity(100)>0);
        assertEquals(80,d.offset(),0.001);
        d.measuredScroll(-60,160);
        assertTrue(d.velocity(160)<0);
        assertEquals(20,d.offset(),0.001);
        assertEquals(0,d.velocity(610),0.001);
        assertEquals(0,d.idleDelay(610));
    }
    @Test public void onlyFreshSnapshotRecoversTrust(){
        TranslationDemand d=new TranslationDemand();
        d.snapshot(1000);
        d.invalidate();
        d.measuredScroll(40,200);
        assertFalse(d.trusted());
        assertEquals(0,d.offset(),0.001);
        d.snapshot(1000);
        assertTrue(d.trusted());
        assertEquals(0,d.offset(),0.001);
        d.measuredScroll(10,240);
        assertEquals(10,d.offset(),0.001);
    }
}
