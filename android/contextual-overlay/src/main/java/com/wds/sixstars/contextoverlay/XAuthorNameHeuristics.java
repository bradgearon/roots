package com.wds.sixstars.contextoverlay;

/** Pure Java detection support; geometry/actual View data stay in X reader. */
final class XAuthorNameHeuristics {
    private XAuthorNameHeuristics() { }

    static boolean isNameId(String resource) {
        if (resource == null) return false;
        String id = resource.toLowerCase(java.util.Locale.ROOT);
        return id.contains("author_name") || id.contains("display_name")
                || id.contains("profile_name") || id.contains("user_name")
                || id.contains("username_text") || id.contains("fullname")
                || id.contains("full_name") || id.contains("screen_name")
                || id.contains("name_text");
    }

    /**
     * Resource IDs are inconsistent across X builds. Keep generic direct-text
     * header candidates long enough to bind them geometrically to an avatar.
     * A possible name is NOT automatically a post and NOT automatically a
     * confirmed author until the avatar/header rules succeed.
     */
    /**
     * X sometimes exposes the author as "Name @handle · 39m" in a custom
     * accessible View, or exposes the name and @handle as separate children.
     */
    static boolean looksLikeHandle(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (!s.startsWith("@") || s.length() < 3 || s.length() > 48
                || s.indexOf(' ') >= 0 || s.indexOf('\n') >= 0)
            return false;
        for (int i = 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '.'
                    || ch == '\u2026')) return false;
        }
        return true;
    }

    static boolean looksCompositeHeader(String text) {
        if (text == null || text.length() > 155) return false;
        String s = text.trim();
        int at = s.indexOf('@');
        if (at < 2 || at > 70 || s.indexOf('\n') >= 0) return false;
        int end = at + 1;
        while (end < s.length()) {
            char ch = s.charAt(end);
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '.'
                    || ch == '\u2026')) break;
            end++;
        }
        if (end - at < 3) return false;
        String trailing = s.substring(end).trim();
        // X's header normally includes a dot-separated elapsed time. For
        // an omitted time, limit the shape to very short name+handle lines.
        if (trailing.startsWith("\u00b7") || trailing.startsWith("\u2022"))
            return true;
        return trailing.isEmpty() && at < 45 && s.length() < 95
                && s.substring(0, at).trim().split("\\s+").length <= 4;
    }

    static boolean hasVisibleEllipsis(String text) {
        if (text == null) return false;
        return text.contains("\u2026") || text.contains("...");
    }

    static boolean mayBeAuthorLabel(String text, String resource,
            String className, boolean fromDescription) {
        if (text == null || text.trim().length() < 2 || text.length() > 155)
            return false;
        if (isNameId(resource) || looksLikeHandle(text)) return true;
        // A composite "Name @handle · 2h" accessibility description can be
        // more informative than any TextView children. Do not classify a
        // generic long post description as an author's name.
        if (fromDescription) return looksCompositeHeader(text);
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) return false;
        if (looksCompositeHeader(text)) return true;
        return !PostTextHeuristics.isUiLabel(text, resource, className);
    }

    static boolean looksTruncated(String text, float estimatedTextWidth,
            int actualWidth) {
        if (text == null || text.trim().isEmpty()) return false;
        String trimmed = text.trim();
        // A visible ellipsis is strong evidence even if X has already
        // shortened the text returned by AccessibilityNodeInfo.getText().
        if (hasVisibleEllipsis(trimmed)) return true;
        return actualWidth > 0 && estimatedTextWidth > actualWidth * 1.17f + 8f;
    }

    static boolean plausibleHeader(int nameLeft, int nameTop, int nameRight,
            int nameBottom, int bodyLeft, int bodyTop,
            int avatarLeft, int avatarTop, int avatarRight, int avatarBottom,
            int screenWidth, boolean knownNameId) {
        int avatarHeight = avatarBottom - avatarTop;
        if (avatarHeight <= 0 || nameRight <= nameLeft || nameBottom <= nameTop)
            return false;
        if (nameLeft < avatarRight - Math.max(6, screenWidth / 75)
                || nameLeft > bodyLeft + Math.max(32, screenWidth / 12))
            return false;
        // A name sits at the avatar's TOP and precedes the body. Do not
        // confuse a short Japanese first line with the author's header.
        if (nameTop < avatarTop - avatarHeight / 3
                || nameTop > avatarTop + (avatarHeight * 3 / 5))
            return false;
        if (nameTop >= bodyTop - Math.max(4, screenWidth / 140)) return false;
        if (nameBottom > bodyTop + Math.max(4, screenWidth / 140)) return false;
        if (bodyTop - nameBottom > Math.max(screenWidth / 4, avatarHeight * 2))
            return false; // Do not attach the previous tweet's author.
        return knownNameId || (nameBottom - nameTop <= avatarHeight * 4 / 5);
    }

    /**
     * Rare X builds omit a readable avatar ImageView entirely. Permit an
     * inferred gutter anchor only with a recognizable X author resource ID
     * or a compact name+handle header, never with arbitrary post text.
     */
    static boolean plausibleHeaderWithoutAvatar(int nameLeft, int nameTop,
            int nameRight, int nameBottom, int bodyLeft, int bodyTop,
            int screenWidth, boolean knownNameId, boolean compositeHeader) {
        if (!(knownNameId || compositeHeader) || screenWidth <= 0
                || nameRight <= nameLeft || nameBottom <= nameTop
                || bodyLeft < screenWidth / 7) return false;
        if (Math.abs(nameLeft - bodyLeft) > Math.max(24, screenWidth / 15))
            return false;
        int gap = bodyTop - nameBottom;
        return nameTop >= 0 && gap >= -Math.max(6, screenWidth / 90)
                && gap <= Math.max(80, screenWidth / 4)
                && nameBottom - nameTop <= Math.max(68, screenWidth / 5);
    }

    static boolean likelyNameAtAvatar(int textTop, int textBottom,
            int avatarTop, int avatarBottom, boolean knownPostTextId) {
        if (knownPostTextId || avatarBottom <= avatarTop || textBottom <= textTop)
            return false;
        int h = avatarBottom - avatarTop;
        return textTop <= avatarTop + h / 3
                && textBottom <= avatarBottom + h / 6
                && textBottom - textTop < h * 4 / 5;
    }
}
