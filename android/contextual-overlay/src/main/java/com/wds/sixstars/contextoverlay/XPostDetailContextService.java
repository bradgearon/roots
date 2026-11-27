package com.wds.sixstars.contextoverlay;

import java.util.Locale;

/**
 * Identifies X's single-post/thread detail header from an accessibility node.
 * X exposes different view IDs on the feed and a dedicated post screen; this
 * helper is deliberately independent of resource ID availability.
 *
 * Only a visible, non-clickable heading near the TOP of X can signal a detail
 * screen. Ordinary "Post" buttons, composer actions and posts in the feed
 * must not accidentally change the candidate ranking.
 */
final class XPostDetailContextService {
    private XPostDetailContextService() { }

    /** Strong X resource IDs can identify a detail screen without a title node. */
    static boolean isDetailContainerId(String resourceId) {
        if (resourceId == null) return false;
        String id = resourceId.toLowerCase(Locale.ROOT);
        return id.contains("tweet_detail") || id.contains("post_detail")
                || id.contains("status_detail") || id.contains("tweet_permalink");
    }

    static boolean isReplyContextId(String resourceId) {
        if(resourceId==null)return false;
        String id=resourceId.toLowerCase(Locale.ROOT);
        return id.contains("reply_composer") || id.contains("reply_context") ||
                id.contains("reply_preview") || id.contains("reply_editor") ||
                id.contains("reply_edit_text") || id.contains("reply_to_tweet");
    }

    static boolean isDetailHeading(String text, String resourceId, String className,
            boolean clickable, boolean editable, int top, int bottom, int screenHeight) {
        if (text == null || screenHeight <= 0 || editable || clickable ||
                top < 0 || top > screenHeight / 4 || bottom <= top ||
                bottom - top > screenHeight / 8) return false;
        String label = text.trim().toLowerCase(Locale.ROOT);
        if (!("post".equals(label) || "post details".equals(label) ||
                "thread".equals(label) || "conversation".equals(label) ||
                "tweet".equals(label) || "ポスト".equals(label) ||
                "投稿".equals(label) || "スレッド".equals(label))) return false;
        String id = resourceId == null ? "" : resourceId.toLowerCase(Locale.ROOT);
        String cls = className == null ? "" : className.toLowerCase(Locale.ROOT);
        // A non-clickable TextView at the very top is a plausible toolbar title
        // even on X versions that don't expose a stable resource ID.
        return id.contains("title") || id.contains("toolbar") ||
                cls.contains("textview");
    }

    static int rank(int bodyTop, int bodyCenterY, int bodyScore,
            boolean knownBodyId, boolean fromDescription, boolean detailScreen,
            int viewportCenterY) {
        // On a detail page the opened post is usually the first large text
        // beneath the title, above replies and recommendations. Prioritize its
        // screen position without needing a stable resource ID.
        int score = bodyScore;
        if (knownBodyId) score += 150;
        if (fromDescription) score -= 25;
        if (detailScreen) {
            score += Math.max(0, 220 - Math.abs(bodyTop) / 3);
        } else {
            score += Math.max(0, 70 - Math.abs(bodyCenterY - viewportCenterY) / 15);
        }
        return score;
    }

}
