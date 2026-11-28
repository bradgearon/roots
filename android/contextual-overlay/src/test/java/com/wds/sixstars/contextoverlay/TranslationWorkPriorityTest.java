package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationWorkPriorityTest {
    private long rank(boolean visible,boolean name,int top,int bottom,double velocity,long latency,long age,long absent) {
        return TranslationWorkPriority.score(visible,name,top,bottom,1000,0,velocity,latency,age,absent);
    }
    @Test public void stationaryReadingOrderAndBodyBeforeName(){
        assertTrue(rank(true,false,100,200,0,260,0,0)>rank(true,false,800,900,0,260,0,0));
        assertTrue(rank(true,false,800,900,0,260,0,0)>rank(true,true,100,200,0,260,0,0));
    }
    @Test public void backgroundNeverOvertakesVisible(){
        assertTrue(rank(true,true,800,900,0,260,0,0)>rank(false,false,100,200,0,260,120000,500));
        assertTrue(rank(false,false,100,200,0,260,0,1000)>0);
        assertEquals(0,rank(false,false,100,200,0,260,0,16000));
        assertEquals(0,rank(false,false,100,200,0.5,260,0,1000));
    }
    @Test public void downscrollAnticipatesLowerPost(){
        assertTrue(rank(true,false,650,750,1,300,0,0)>rank(true,false,100,200,1,300,0,0));
    }
    @Test public void upscrollAnticipatesUpperPost(){
        assertTrue(rank(true,false,80,180,-1,300,0,0)>rank(true,false,700,800,-1,300,0,0));
    }
    @Test public void slowBackendLooksFartherAhead(){
        assertTrue(rank(true,false,420,520,0.5,150,0,0)>rank(true,false,800,900,0.5,150,0,0));
        assertTrue(rank(true,false,800,900,0.5,900,0,0)>rank(true,false,420,520,0.5,900,0,0));
    }
    @Test public void deferContentLeavingDuringFlingAndUseMeasuredOffset(){
        assertEquals(0,rank(true,false,100,200,8,300,0,0));
        assertEquals(0,TranslationWorkPriority.score(true,false,100,200,1000,250,0,260,0,0));
    }
}
