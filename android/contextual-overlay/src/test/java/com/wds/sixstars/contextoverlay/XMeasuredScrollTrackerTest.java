package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** No fabricated motion: accept exact delta/offset measurements only. */
public final class XMeasuredScrollTrackerTest {
    @Test public void directScrollDeltaMovesTheSceneByExactPixels() {
        XMeasuredScrollTracker tracker = new XMeasuredScrollTracker();
        XMeasuredScrollTracker.Result first = tracker.observe("x:feed", 47, -1);
        assertTrue(first.measured);
        assertEquals(47, first.deltaY);
        XMeasuredScrollTracker.Result back = tracker.observe("x:feed", -25, -1);
        assertTrue(back.measured);
        assertEquals(-25, back.deltaY);
    }

    @Test public void absoluteOffsetsAreExactFallbackNotVelocity() {
        XMeasuredScrollTracker tracker = new XMeasuredScrollTracker();
        assertFalse(tracker.observe("x:feed", 0, 200).measured);
        XMeasuredScrollTracker.Result next = tracker.observe("x:feed", 0, 255);
        assertTrue(next.measured);
        assertEquals(55, next.deltaY);
        XMeasuredScrollTracker.Result stopped = tracker.observe("x:feed", 0, 255);
        assertTrue(stopped.measured);
        assertEquals(0, stopped.deltaY);
    }

    @Test public void neverSubtractsOffsetsFromDifferentScrollContainers() {
        XMeasuredScrollTracker tracker = new XMeasuredScrollTracker();
        tracker.observe("x:feed", 0, 120);
        assertFalse(tracker.observe("x:carousel", 0, 210).measured);
        assertEquals(15, tracker.observe("x:carousel", 0, 225).deltaY);
    }

    @Test public void missingMeasurementRequiresAnAuthoritativeSnapshot() {
        XMeasuredScrollTracker tracker = new XMeasuredScrollTracker();
        assertFalse(tracker.observe("x:feed", 0, -1).measured);
        assertFalse(tracker.observe("x:feed", 0, -1).measured);
        tracker.observe("x:feed", 0, 10);
        tracker.reset();
        assertFalse(tracker.observe("x:feed", 0, 100).measured);
    }
}
