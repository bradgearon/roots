package com.wds.sixstars.contextoverlay;

/**
 * Two role-specific controls per X post: a NAME action underneath
 * the avatar when clipped, and a thumb-side TWEET action only when the translation is clipped.
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

    /**
     * Thumb-side tweet expansion. The name-details button is different and
     * stays below the avatar. This control lives at the right of the source
     * text, and is only materialized when translated text is actually clipped.
     */
    static Placement locateTweet(int bodyLeft, int bodyTop, int bodyRight,
            int bodyBottom, int cardRight, int screenWidth, int screenHeight,
            float density) {
        if (bodyRight <= bodyLeft || bodyBottom <= bodyTop) return null;
        int w = dp(38, density);
        int h = dp(30, density);
        int rightEdge = Math.min(screenWidth - dp(5, density),
                Math.min(bodyRight - dp(3, density),
                        cardRight > bodyLeft ? cardRight - dp(8, density)
                                : bodyRight - dp(3, density)));
        int x = rightEdge - w;
        // Keep the action alongside the source text rather than on the
        // engagement row or across a neighboring tweet.
        int y = bodyTop + Math.min(dp(4, density), Math.max(0, bodyBottom - bodyTop - h));
        if (x <= bodyLeft || x + w > screenWidth || y < 0
                || y + h > screenHeight) return null;
        return new Placement(x, y, w, h);
    }

    /**
     * Put account-name details strictly below the avatar. X often returns
     * a body-only postBounds that ends before the PFP plus its control, so
     * using cardBottom as a hard gate made valid badges disappear.
     *
     * The avatar may be an observed image or a high-confidence inferred
     * gutter rectangle from a verified author header.
     */
    static Placement locateName(int bodyLeft, int bodyTop, int bodyRight,
            int bodyBottom, int cardLeft, int cardTop, int cardRight,
            int cardBottom, boolean hasCard, int avatarLeft, int avatarTop, int avatarRight,
            int avatarBottom, int screenWidth, int screenHeight, float density) {
        if (avatarRight <= avatarLeft || avatarBottom <= avatarTop
                || avatarTop < dp(24, density)) return null;
        int width = dp(32, density);
        int height = dp(30, density);
        int gap = dp(4, density);
        int gutterRight = bodyLeft - gap;
        int x = Math.min(avatarLeft + (avatarRight - avatarLeft - width) / 2,
                gutterRight - width);
        int y = avatarBottom + gap;
        // Never float the badge over the actual PFP, username or status bar.
        // Card bounds are intentionally NOT a gate: X frequently reports
        // only the text body as the enclosing "post" node.
        if (x < 0 || x + width > gutterRight || y < 0
                || x + width > screenWidth || y + height > screenHeight)
            return null;
        return new Placement(x, y, width, height);
    }

    private static int dp(int value, float density) {
        return Math.max(1, Math.round(value * Math.max(0.5f, density)));
    }
}
