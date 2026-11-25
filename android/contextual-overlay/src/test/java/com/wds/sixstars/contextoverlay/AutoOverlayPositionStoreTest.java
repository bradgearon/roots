package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/**
 * Exact measured-scroll movement, authoritative snap correction and transient
 * reply-page tree gaps. No Android SDK/device and no model are required.
 */
public final class AutoOverlayPositionStoreTest {
    private static AutoInlinePlacementService.Placement at(int x, int y) {
        return AutoInlinePlacementService.locate(
                x, y, x + 360, y + 80, 440, 920, 1f);
    }

    private static AutoOverlayPositionStore.Observation post(String id, int x, int y) {
        return new AutoOverlayPositionStore.Observation(id, at(x, y));
    }

    @Test public void exactMeasuredPixelsMoveTranslatedBody() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        store.publish(Arrays.asList(post("post-one", 40, 260)), false, 100);
        AutoInlinePlacementService.Placement original =
                store.snapshot(100).get(0).rectangle;

        store.applyMeasuredScroll(0, 57);
        AutoInlinePlacementService.Placement moved =
                store.snapshot(101).get(0).rectangle;
        assertEquals(original.y - 57, moved.y);
        assertEquals(original.width, moved.width);
        assertEquals(original.height, moved.height);
        assertEquals(original.x, moved.x);
    }

    @Test public void detailsActionAndTranslatedBodyHaveOneSharedMeasuredPosition() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        AutoDetailsPlacementService.Placement details =
                new AutoDetailsPlacementService.Placement(47, 289, 32, 30);
        store.publish(Arrays.asList(new AutoOverlayPositionStore.Observation(
                "one", at(130, 260), details)), false, 100);
        assertEquals(289, store.snapshot(100).get(0).details.y);

        store.applyMeasuredScroll(0, 57);
        AutoOverlayPositionStore.Located moved = store.snapshot(101).get(0);
        assertEquals(203, moved.rectangle.y);
        assertEquals(232, moved.details.y);

        // A fresh X source rectangle is authoritative; no double scrolling.
        AutoDetailsPlacementService.Placement fresh =
                new AutoDetailsPlacementService.Placement(47, 234, 32, 30);
        store.publish(Arrays.asList(new AutoOverlayPositionStore.Observation(
                "one", at(130, 262), fresh)), true, 120);
        assertEquals(262, store.snapshot(120).get(0).rectangle.y);
        assertEquals(234, store.snapshot(120).get(0).details.y);
    }

    @Test public void nextXSnapshotReplacesMotionOffsetInsteadOfDoubleApplyingIt() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        store.publish(Arrays.asList(post("post-one", 40, 300)), false, 100);
        store.applyMeasuredScroll(0, 42);
        assertEquals(258, store.snapshot(110).get(0).rectangle.y);

        store.publish(Arrays.asList(post("post-one", 40, 261)), true, 125);
        assertEquals(261, store.snapshot(125).get(0).rectangle.y);
        assertEquals(1, store.snapshot(125).size());
    }

    @Test public void uncertainMovementHidesUntilMeasuredAgain() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        store.publish(Arrays.asList(post("one", 40, 220)), false, 100);
        store.suspendUntilMeasured();
        assertFalse(store.isTrusted());
        assertTrue(store.snapshot(102).isEmpty());

        store.publish(Arrays.asList(post("one", 40, 214)), false, 130);
        assertTrue(store.isTrusted());
        assertEquals(214, store.snapshot(130).get(0).rectangle.y);
    }

    @Test public void temporaryStationaryTreeGapDoesNotBlink() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        store.publish(Arrays.asList(post("one", 40, 350)), false, 100);
        store.publish(Collections.emptyList(), false, 200);
        assertEquals(1, store.snapshot(800).size());
        assertTrue(store.snapshot(1300).isEmpty());
    }

    @Test public void enteringStatusBarHidesTheWholePostInsteadOfCoveringClock() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920, 48);
        store.publish(Arrays.asList(post("one", 70, 52)), false, 100);
        assertEquals(1, store.snapshot(100).size());
        store.applyMeasuredScroll(0, 6);
        assertTrue(store.snapshot(101).isEmpty());
        store.publish(Arrays.asList(post("one", 70, 54)), true, 120);
        assertEquals(1, store.snapshot(120).size());
    }

    @Test public void scrollEvictsDepartedPostsImmediately() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        store.publish(Arrays.asList(post("one", 40, 350)), false, 100);
        store.publish(Collections.emptyList(), true, 200);
        assertTrue(store.snapshot(201).isEmpty());
    }

    @Test public void unseenPostCannotReceiveALateTranslation() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        store.publish(Arrays.asList(post("one", 40, 350)), false, 100);
        assertTrue(store.contains("one"));
        assertFalse(store.contains("two"));
        store.clear();
        assertFalse(store.contains("one"));
        assertTrue(store.snapshot(105).isEmpty());
    }

    @Test public void scrollOffscreenHidesInsteadOfPinningPostAtEdge() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        store.publish(Arrays.asList(post("one", 40, 60)), false, 100);
        store.applyMeasuredScroll(0, 85);
        assertTrue(store.snapshot(101).isEmpty());
    }
}
