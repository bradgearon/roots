package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

public final class XSceneGeometryClockTest {
    @Test public void lateEventAlreadyIncludedInBoundsDoesNotInvalidateScene(){
        var clock=new XSceneGeometryClock();
        clock.snapshot(1000);
        assertTrue(clock.covers(950));
        assertFalse(clock.motion(950,1050));
        assertFalse(clock.expired(2000));
    }
    @Test public void repeatedScrollEventsCannotExtendStaleGeometryDeadline(){
        var clock=new XSceneGeometryClock();
        clock.snapshot(1000);
        assertTrue(clock.motion(1100,1110));
        assertEquals(1350L,clock.deadline());
        assertTrue(clock.motion(1200,1210));
        assertFalse(clock.motion(1200,1220));
        assertEquals(1350L,clock.deadline());
        assertFalse(clock.expired(1349));
        assertTrue(clock.expired(1350));
        assertFalse(clock.expired(1400));
    }
    @Test public void measuredSnapshotClearsPendingMotionWithoutBlanking(){
        var clock=new XSceneGeometryClock();
        clock.snapshot(1000);
        assertTrue(clock.motion(1100,1102));
        clock.snapshot(1150);
        assertFalse(clock.expired(1500));
        assertFalse(clock.motion(1100,1600));
        assertTrue(clock.motion(1700,1701));
        assertEquals(1941L,clock.deadline());
    }
    @Test public void windowChangeClearsTheOldTimeline(){
        var clock=new XSceneGeometryClock();
        clock.snapshot(2000);clock.reset();
        assertTrue(clock.motion(1500,2100));
        assertFalse(clock.expired(2339));
        assertTrue(clock.expired(2340));
    }
}
