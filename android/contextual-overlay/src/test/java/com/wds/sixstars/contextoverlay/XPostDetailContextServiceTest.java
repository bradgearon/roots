package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Dedicated post/thread detection must not change ordinary timeline scoring. */
public final class XPostDetailContextServiceTest {
    @Test public void detectsDedicatedDetailViewResources() {
        assertTrue(XPostDetailContextService.isDetailContainerId(
                "com.twitter.android:id/tweet_detail_container"));
        assertTrue(XPostDetailContextService.isDetailContainerId(
                "com.twitter.android:id/post_detail"));
        assertFalse(XPostDetailContextService.isDetailContainerId(
                "com.twitter.android:id/timeline_item"));
        assertFalse(XPostDetailContextService.isDetailContainerId(null));
    }

    @Test public void detectsPostDetailToolbarWithAndWithoutStableId() {
        assertTrue(XPostDetailContextService.isDetailHeading(
                "Post", "com.twitter.android:id/toolbar_title",
                "android.widget.TextView", false, false,
                70, 120, 1600));
        assertTrue(XPostDetailContextService.isDetailHeading(
                "Thread", null, "android.widget.TextView",
                false, false, 70, 120, 1600));
        assertTrue(XPostDetailContextService.isDetailHeading(
                "ポスト", null, "android.widget.TextView",
                false, false, 70, 120, 1600));
    }

    @Test public void ignoresPostActionsAndLabelsOutsideTheToolbar() {
        assertFalse(XPostDetailContextService.isDetailHeading(
                "Post", null, "android.widget.Button",
                true, false, 70, 120, 1600));
        assertFalse(XPostDetailContextService.isDetailHeading(
                "Post", null, "android.widget.TextView",
                false, false, 900, 950, 1600));
        assertFalse(XPostDetailContextService.isDetailHeading(
                "Reply", "com.twitter.android:id/toolbar_title",
                "android.widget.TextView", false, false, 70, 120, 1600));
    }

    @Test public void detailRankingPrefersOpenedPostAboveReplies() {
        int primary = XPostDetailContextService.rank(
                120, 250, 95, false, false, true, 850);
        int reply = XPostDetailContextService.rank(
                980, 1080, 100, false, false, true, 850);
        assertTrue(primary > reply);
        int explicitBody = XPostDetailContextService.rank(
                250, 350, 120, true, false, true, 850);
        assertTrue(explicitBody > reply);
    }
}
