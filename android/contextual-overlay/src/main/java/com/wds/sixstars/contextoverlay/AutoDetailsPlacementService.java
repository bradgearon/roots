package com.wds.sixstars.contextoverlay;

/**
 * One role-specific control per translated X post: a details action underneath
 * that post's avatar, never inside the name/header or translated body.
 *
 * Pure pixel geometry: the text body, post card and (when X exposes it) avatar
 * have distinct meanings. Independent of Android widgets and testable on JVM.
 */
final class AutoDetailsPlacementService {
    static final class Placement {
        final int x;
        final int y;
        final int width;
        final int height;

        Placement(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        Placement shifted(int dx, int dy) {
            return new Placement(x + dx, y + dy, width, height);
        }

        boolean sameAs(Placement other) {
            return other != null && x == other.x && y == other.y
                    && width == other.width && height == other.height;
        }
    }

    static Placement locate(int bodyLeft, int bodyTop, int bodyRight, int bodyBottom,
            int cardLeft, int cardTop, int cardRight, int cardBottom,
            boolean hasCard, int avatarLeft, int avatarTop, int avatarRight,
            int avatarBottom, int screenWidth, int screenHeight, float density) {
        if (bodyRight <= bodyLeft || bodyBottom <= bodyTop
                || screenWidth <= 0 || screenHeight <= 0) return null;

        // Earlier controls were ~21dp tall. This is a little taller, but
        // remains in the avatar gutter instead of covering the tweet body.
        int width = dp(32, density);
        int height = dp(30, density);
        int gutterRight = bodyLeft - dp(4, density);
        if (gutterRight < width) return null;

        boolean hasAvatar = avatarRight > avatarLeft && avatarBottom > avatarTop;
        int avatarCenter = hasAvatar ? avatarLeft + (avatarRight - avatarLeft) / 2
                : hasCard && cardLeft < bodyLeft - dp(16, density)
                        ? cardLeft + (bodyLeft - cardLeft) / 2
                        : bodyLeft - dp(30, density);
        int x = Math.max(0, Math.min(avatarCenter - width / 2, gutterRight - width));

        // Prefer the observed profile image. For X releases that do not expose
        // an avatar node, infer the gutter from the body/card relationship.
        int y = hasAvatar ? avatarBottom + dp(4, density)
                : hasCard && cardBottom > cardTop
                        ? Math.max(bodyTop + dp(7, density),
                                cardTop + dp(51, density))
                        : bodyTop + dp(8, density);

        // Never clamp a scrolled-away control to the viewport edge or let it
        // spill outside its known card into the next post.
        if (y < 0 || y + height > screenHeight) return null;
        if (hasCard && cardBottom > cardTop && y + height > cardBottom - dp(2, density))
            return null;
        if (hasAvatar && (avatarRight > bodyLeft || avatarBottom > y)) return null;
        return new Placement(x, y, width, height);
    }

    private static int dp(int value, float density) {
        return Math.max(1, Math.round(value * Math.max(0.5f, density)));
    }
}
