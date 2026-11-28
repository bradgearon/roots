package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Separate author/header classification from translated post-body matching. */
public final class XAuthorNameHeuristicsTest {
    @Test public void recognizesAuthorResourceIdsButNotTweetBodies() {
        assertTrue(XAuthorNameHeuristics.isNameId("com.twitter.android:id/display_name"));
        assertTrue(XAuthorNameHeuristics.isNameId("com.twitter.android:id/author_name"));
        assertFalse(XAuthorNameHeuristics.isNameId("com.twitter.android:id/tweet_text"));
        assertFalse(XAuthorNameHeuristics.isNameId(null));
    }

    @Test public void customXViewCanSupplyAnAuthorWithoutAStableId() {
        assertTrue(XAuthorNameHeuristics.mayBeAuthorLabel(
                "サブ者*sub", null, "android.view.View", false));
        assertTrue(XAuthorNameHeuristics.mayBeAuthorLabel(
                "Long display name", null, "android.widget.TextView", false));
        assertTrue(XAuthorNameHeuristics.mayBeAuthorLabel(
                "Name from X", "com.twitter.android:id/author_name",
                "android.view.View", true));
        assertTrue(XAuthorNameHeuristics.mayBeAuthorLabel(
                "@handle", null, "android.view.View", false));
        assertFalse(XAuthorNameHeuristics.mayBeAuthorLabel(
                "Reply", null, "android.view.View", false));
        assertFalse(XAuthorNameHeuristics.mayBeAuthorLabel(
                "Tweet body paragraph", null, "android.view.View", true));
    }

    @Test public void recognizesCombinedAndTruncatedNameHandleHeaders() {
        assertTrue(XAuthorNameHeuristics.looksCompositeHeader(
                "Nana @hunahunasekai… · 10h"));
        assertTrue(XAuthorNameHeuristics.looksCompositeHeader(
                "長いお名前 @japanese_account... · 31m"));
        assertTrue(XAuthorNameHeuristics.mayBeAuthorLabel(
                "Nana @hunahunasekai… · 10h",
                null, "android.view.View", true));
        assertTrue(XAuthorNameHeuristics.looksLikeHandle("@username..."));
        assertTrue(XAuthorNameHeuristics.hasVisibleEllipsis(
                "Nana @username... · 10h"));
        assertFalse(XAuthorNameHeuristics.looksLikeHandle(
                "Tweet @someone is good"));
        assertFalse(XAuthorNameHeuristics.mayBeAuthorLabel(
                "Some random post description", null, "android.view.View", true));
    }

    @Test public void canAssociateStrongXHeaderWithAnInferredProfileGutter() {
        assertTrue(XAuthorNameHeuristics.plausibleHeaderWithoutAvatar(
                130, 205, 400, 237, 130, 263, 440, true, false));
        assertTrue(XAuthorNameHeuristics.plausibleHeaderWithoutAvatar(
                130, 205, 400, 237, 130, 263, 440, false, true));
        assertFalse(XAuthorNameHeuristics.plausibleHeaderWithoutAvatar(
                130, 205, 400, 237, 130, 263, 440, false, false));
        assertFalse(XAuthorNameHeuristics.plausibleHeaderWithoutAvatar(
                25, 205, 400, 237, 130, 263, 440, true, false));
    }

    @Test public void ellipsizedNameNeedsItsOwnDetailsControl() {
        assertTrue(XAuthorNameHeuristics.looksTruncated("Sora Yuumi…", 100f, 180));
        assertTrue(XAuthorNameHeuristics.looksTruncated("A very long user", 260f, 110));
        assertFalse(XAuthorNameHeuristics.looksTruncated("Short name", 75f, 180));
    }

    @Test public void acceptsNameAboveBodyNextToSameAvatar() {
        assertTrue(XAuthorNameHeuristics.plausibleHeader(
                130, 205, 325, 236, 130, 256,
                22, 203, 104, 283, 440, true));
        assertFalse(XAuthorNameHeuristics.plausibleHeader(
                130, 310, 390, 340, 130, 256,
                22, 203, 104, 283, 440, false));
    }

    @Test public void avoidsTreatingAvatarTopAuthorLineAsTweetBody() {
        assertTrue(XAuthorNameHeuristics.likelyNameAtAvatar(
                210, 234, 203, 283, false));
        assertFalse(XAuthorNameHeuristics.likelyNameAtAvatar(
                260, 295, 203, 283, false));
        assertFalse(XAuthorNameHeuristics.likelyNameAtAvatar(
                210, 234, 203, 283, true));
    }
}
