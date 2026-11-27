package com.wds.sixstars.contextoverlay;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Conservative, source-agnostic post-body selection rules. X's resource IDs
 * differ between releases: explicit post-text IDs take precedence, with a
 * geometry/text fallback when only generic accessible Views are exposed.
 *
 * Pure Java to permit JVM unit tests without a device or accessibility tree.
 */
final class PostTextHeuristics {
    private static final Pattern COUNTER = Pattern.compile(
            "^(?:[0-9][0-9,.]*[kmb]?)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME = Pattern.compile(
            "^(?:[0-9]+[smhdwy]|[0-9]{1,2}:[0-9]{2}(?:\\s*[ap]m)?|"
                    + "today|yesterday|[a-z]{3}\\s+[0-9]{1,2})$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDLE = Pattern.compile("^@[a-z0-9_]{1,25}$",
            Pattern.CASE_INSENSITIVE);

    private PostTextHeuristics() { }

    static boolean isPostTextId(String id) {
        String s = lower(id);
        return s.contains("tweet_text") || s.contains("tweettext") ||
                s.contains("tweet_body") || s.contains("tweetbody") ||
                s.contains("status_text") || s.contains("post_text") ||
                s.contains("post_body") || s.contains("text_tweet") ||
                s.contains("tweet_detail_text") || s.contains("tweet_detail_body") ||
                s.contains("post_detail_text") || s.contains("post_detail_body") ||
                s.contains("tweet_content_text") || s.contains("reply_context_text") ||
                s.contains("reply_preview_text");
    }

    static boolean isPostContainerId(String id) {
        String s = lower(id);
        if (isPostTextId(s)) return false;
        return s.contains("tweet_container") || s.contains("tweet_row") ||
                s.contains("tweet_item") || s.contains("tweet_view") ||
                s.contains("timeline_item") || s.contains("status_container") ||
                s.contains("post_container") || s.contains("post_cell");
    }

    static boolean isListClass(String className) {
        String s = lower(className);
        return s.contains("recyclerview") || s.contains("listview");
    }

    static boolean isUiLabel(String text, String viewId, String className) {
        String s = lower(text).trim();
        String id = lower(viewId);
        String cls = lower(className);
        if (s.isEmpty()) return true;
        // ImageViews carry alt/profile/media labels, never tweet-body text.
        if (cls.contains("imageview") || cls.contains("imagebutton") || cls.contains("compoundbutton") ||
                cls.endsWith(".button")) return true;

        if (!isPostTextId(id) && (XAuthorNameHeuristics.isNameId(id)
                || id.contains("toolbar") || id.contains("tab_bar") ||
                id.contains("navigation") || id.contains("action_button") ||
                id.contains("avatar") || id.contains("timestamp") ||
                id.contains("author_name") || id.contains("profile_name") ||
                id.contains("follow_button") || id.contains("repost_count") ||
                id.contains("like_count") || id.contains("view_count"))) return true;

        // A composite X header (Name @handle · 2h) is an author label,
        // even when custom Views omit the usual author_name resource ID.
        // Require the timestamp separator before excluding it as a tweet.
        if ((s.contains("·") || s.contains("•"))
                && XAuthorNameHeuristics.looksCompositeHeader(text))
            return true;

        if (COUNTER.matcher(s).matches() || TIME.matcher(s).matches() ||
                HANDLE.matcher(s).matches()) return true;

        if (s.equals("reply") || s.equals("repost") || s.equals("retweet") ||
                s.equals("like") || s.equals("liked") || s.equals("share") ||
                s.equals("bookmark") || s.equals("quote") || s.equals("post") ||
                s.equals("follow") || s.equals("following") || s.equals("subscribe") ||
                s.equals("for you") || s.equals("notifications") ||
                s.equals("home") || s.equals("search") || s.equals("messages") ||
                s.equals("show more") || s.equals("translate post") ||
                s.equals("view post engagements") || s.equals("what's happening") ||
                s.equals("posts") || s.equals("media") || s.equals("replies") ||
                s.equals("your account") || s.equals("trending")) return true;

        if (s.startsWith("reposted by ") || s.startsWith("replying to ") ||
                s.startsWith("liked by ") || s.startsWith("followed by ") ||
                s.startsWith("trending in ") || s.startsWith("see new posts") ||
                s.startsWith("show this thread") || s.startsWith("search x") ||
                s.startsWith("search twitter") || s.startsWith("post your reply")) return true;
        return false;
    }

    static int score(String text, String id, String className, boolean fromDescription,
                     boolean clickable, int width, int height, String languageHint) {
        return score(text,id,className,fromDescription,clickable,width,height,languageHint,false);
    }

    static int score(String text, String id, String className, boolean fromDescription,
                     boolean clickable, int width, int height, String languageHint, boolean bodyContext) {
        if (text == null || isUiLabel(text, id, className)) return -1;
        boolean knownBody = isPostTextId(id);
        boolean foreign = languageHint != null && !languageHint.isEmpty();
        int minChars = knownBody ? 1 : (foreign ? 2 : bodyContext && !fromDescription ? 2 : 12);
        if (text.length() < minChars || text.length() > 1100 ||
                width < 45 || height < 12) return -1;
        if (clickable && !knownBody && !foreign && !bodyContext && text.length() < 65) return -1;

        int score = 30 + Math.min(text.length(), 240) / 4;
        if (knownBody) score += 180;
        if (foreign) score += 40;
        if (height > 32) score += 12;
        if (width > 180) score += 8;
        if (className != null && className.toLowerCase(Locale.ROOT).contains("textview"))
            score += 16;
        if (fromDescription) score -= 18;
        if (clickable && !knownBody) score -= 10;
        if (text.startsWith("@") && text.indexOf(' ') < 0) score -= 70;
        return score;
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
