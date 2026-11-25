package com.wds.sixstars.contextoverlay;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-Java tests; no Android accessibility service or internet required. */
public class PostTextHeuristicsTest {
    @Test public void knownTweetBodyIdentifiersWin() {
        assertTrue(PostTextHeuristics.isPostTextId("com.twitter.android:id/tweet_text"));
        assertTrue(PostTextHeuristics.isPostTextId("com.twitter.android:id/post_body"));
        assertTrue(PostTextHeuristics.isPostContainerId("com.twitter.android:id/tweet_container"));
        assertFalse(PostTextHeuristics.isPostContainerId("com.twitter.android:id/tweet_text"));
    }

    @Test public void navigationAndCountsAreNotPostBodies() {
        assertTrue(PostTextHeuristics.isUiLabel("Reply", null, "android.widget.TextView"));
        assertTrue(PostTextHeuristics.isUiLabel("Repost", null, "android.widget.TextView"));
        assertTrue(PostTextHeuristics.isUiLabel("@some_user", null, "android.widget.TextView"));
        assertTrue(PostTextHeuristics.isUiLabel("12.5K", null, "android.widget.TextView"));
        assertTrue(PostTextHeuristics.isUiLabel("View post engagements",
                "com.twitter.android:id/action_button", "android.widget.TextView"));
    }

    @Test public void japaneseTweetBodyCanBeVeryShort() {
        int score = PostTextHeuristics.score("こんにちは",
                "com.twitter.android:id/tweet_text", "android.widget.TextView",
                false, true, 320, 30, "ja");
        assertTrue(score > 0);
        assertTrue(PostTextHeuristics.score("こんにちは", null, "android.widget.TextView",
                false, false, 320, 30, "ja") > 0);
    }

    @Test public void profileLabelsNeverOutrankBody() {
        int body = PostTextHeuristics.score("日本語で書かれた投稿を翻訳しています",
                "com.twitter.android:id/tweet_text", "android.widget.TextView",
                false, false, 350, 55, "ja");
        int label = PostTextHeuristics.score("Reply", null, "android.widget.Button",
                false, true, 45, 20, "");
        assertTrue(body > 150);
        assertTrue(label < 0);
    }

    @Test public void avatarImageDescriptionsAreNotTranslatablePostBodies() {
        assertTrue(PostTextHeuristics.isUiLabel("プロフィール画像", null,
                "android.widget.ImageView"));
        assertTrue(PostTextHeuristics.isUiLabel("Profile images for a user", null,
                "android.widget.ImageView"));
    }

    @Test public void recyclerViewRowDiscovery() {
        assertTrue(PostTextHeuristics.isListClass("androidx.recyclerview.widget.RecyclerView"));
        assertTrue(PostTextHeuristics.isListClass("android.widget.ListView"));
    }
}
