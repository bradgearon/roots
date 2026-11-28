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

    @Test public void nameAndTweetDetailsMoveWithTheSamePost() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        AutoDetailsPlacementService.Placement tweet =
                new AutoDetailsPlacementService.Placement(369, 254, 38, 30);
        AutoDetailsPlacementService.Placement name =
                new AutoDetailsPlacementService.Placement(47, 287, 32, 30);
        store.publish(Arrays.asList(new AutoOverlayPositionStore.Observation(
                "one", at(130, 250), tweet, name, "Long account name…")),
                false, 100);
        store.applyMeasuredScroll(0, 25);
        AutoOverlayPositionStore.Located moved = store.snapshot(101).get(0);
        assertEquals(225, moved.rectangle.y);
        assertEquals(229, moved.details.y);
        assertEquals(262, moved.nameDetails.y);
        assertEquals("Long account name…", moved.authorName);
    }

    @Test public void aMissingAuthorNodeDoesNotBlinkForTheSamePost() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        AutoDetailsPlacementService.Placement name =
                new AutoDetailsPlacementService.Placement(47, 337, 32, 30);
        store.publish(Arrays.asList(new AutoOverlayPositionStore.Observation(
                "post-one", at(130, 300), null, name, "Long account name…")),
                false, 100);
        // X momentarily emits just the body, even though the tweet remains.
        store.publish(Arrays.asList(post("post-one", 130, 296)), true, 200);
        assertTrue(store.hasTrackedAuthor("post-one", 250));
        assertTrue(store.hasPendingAuthorExpiry());
        assertEquals(333, store.snapshot(250).get(0).nameDetails.y);
        assertEquals("Long account name…", store.snapshot(250).get(0).authorName);

        store.publish(Arrays.asList(post("post-one", 130, 294)), true, 800);
        // Repeated scans must not restart the original 1.1-second grace.
        assertEquals(501L, store.nextExpiryDelayMs(800));
        assertEquals(331, store.snapshot(850).get(0).nameDetails.y);
        // An old name is never kept indefinitely after X stops exposing it.
        assertNull(store.snapshot(1301).get(0).nameDetails);
        assertFalse(store.hasTrackedAuthor("post-one", 1301));
    }

    @Test public void nameReappearsAndResetsItsMissingHeaderGrace() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        AutoDetailsPlacementService.Placement original =
                new AutoDetailsPlacementService.Placement(47, 337, 32, 30);
        store.publish(Arrays.asList(new AutoOverlayPositionStore.Observation(
                "post-one", at(130, 300), null, original, "Japanese display…")),
                false, 100);
        store.publish(Arrays.asList(post("post-one", 130, 298)), false, 200);
        store.publish(Arrays.asList(new AutoOverlayPositionStore.Observation(
                "post-one", at(130, 298), null,
                new AutoDetailsPlacementService.Placement(47, 335, 32, 30),
                "Japanese display…")), false, 400);
        assertFalse(store.hasPendingAuthorExpiry());
        assertNotNull(store.snapshot(2500).get(0).nameDetails);
    }

    @Test public void anotherPostNeverInheritsThePreviousAuthorsControl() {
        AutoOverlayPositionStore store = new AutoOverlayPositionStore(440, 920);
        AutoDetailsPlacementService.Placement name =
                new AutoDetailsPlacementService.Placement(47, 337, 32, 30);
        store.publish(Arrays.asList(new AutoOverlayPositionStore.Observation(
                "first", at(130, 300), null, name, "First author…")),
                false, 100);
        store.publish(Arrays.asList(post("different", 130, 305)), true, 200);
        assertEquals(1, store.snapshot(210).size());
        assertEquals("different", store.snapshot(210).get(0).id);
        assertNull(store.snapshot(210).get(0).nameDetails);
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

    @Test public void measuredDeltaCannotRepairUnknownEarlierDisplacement() {
        AutoOverlayPositionStore store=new AutoOverlayPositionStore(440,920);
        store.publish(Arrays.asList(post("one",40,220)),false,100);
        store.suspendUntilMeasured();
        store.applyMeasuredScroll(0,20);
        assertFalse(store.isTrusted());
        assertTrue(store.snapshot(110).isEmpty());
        store.publish(Collections.emptyList(),false,120);
        assertTrue(store.isTrusted());
        assertTrue(store.snapshot(120).isEmpty());
        store.publish(Arrays.asList(post("one",40,170)),false,130);
        assertEquals(170,store.snapshot(130).get(0).rectangle.y);
    }

    @Test public void replacementAtSameBoundsImmediatelyEvictsOldTranslation(){
        AutoOverlayPositionStore store=new AutoOverlayPositionStore(440,920);
        store.publish(Arrays.asList(post("old",40,350)),false,100);
        store.publish(Arrays.asList(post("new",40,350)),false,200);
        assertEquals(1,store.snapshot(200).size());
        assertEquals("new",store.snapshot(200).get(0).id);
        assertFalse(store.contains("old"));
    }

    @Test public void layoutMovementWithoutScrollEventEvictsMissingPosts(){
        AutoOverlayPositionStore store=new AutoOverlayPositionStore(440,920);
        store.publish(Arrays.asList(post("old",40,200),post("remaining",40,600)),false,100);
        store.publish(Arrays.asList(post("remaining",40,450)),false,200);
        assertEquals(1,store.snapshot(200).size());
        assertEquals(450,store.snapshot(200).get(0).rectangle.y);
        assertFalse(store.contains("old"));
    }

    @Test public void repeatedTextOwnsSeparatePositions(){
        AutoOverlayPositionStore store=new AutoOverlayPositionStore(440,920);
        store.publishScene(Arrays.asList(post("same",40,200),post("same",40,550)),false,100);
        var first=store.snapshot(100);
        assertEquals(2,first.size());
        assertNotEquals(first.get(0).id,first.get(1).id);
        assertEquals("same",AutoOverlayPositionStore.contentId(first.get(0).id));
        assertTrue(store.containsContent("same"));
        store.applyMeasuredScroll(0,50);
        store.publishScene(Arrays.asList(post("same",40,150),post("same",40,500)),true,200);
        var moved=store.snapshot(200);
        assertEquals(first.get(0).id,moved.get(0).id);
        assertEquals(first.get(1).id,moved.get(1).id);
        store.publishScene(Arrays.asList(post("same",40,500)),false,250);
        assertEquals(1,store.snapshot(250).size());
        assertEquals(first.get(1).id,store.snapshot(250).get(0).id);
    }

    @Test public void absentSceneItemsDoNotKeepStaleTextEvenOnStationaryScan(){
        AutoOverlayPositionStore store=new AutoOverlayPositionStore(440,920);
        store.publishScene(Arrays.asList(post("old",40,200),post("other",40,550)),false,100);
        store.publishScene(Arrays.asList(post("other",40,550)),false,200);
        assertFalse(store.containsContent("old"));
        assertEquals(1,store.snapshot(200).size());
        store.publishScene(Collections.emptyList(),false,300);
        assertFalse(store.containsContent("other"));
        assertTrue(store.snapshot(300).isEmpty());
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
