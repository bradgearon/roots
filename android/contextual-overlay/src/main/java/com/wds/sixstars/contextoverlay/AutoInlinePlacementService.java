package com.wds.sixstars.contextoverlay;

/**
 * Body-only replacement bounds for the shared Auto translation Canvas.
 * Buttons have no place inside this rectangle: AutoDetailsPlacementService
 * positions the sole post action in the avatar gutter instead.
 */
public final class AutoInlinePlacementService {
    private AutoInlinePlacementService() { }

    public static Placement locate(int left, int top, int right, int bottom,
            int screenWidth, int screenHeight, float density) {
        if (screenWidth <= 0 || screenHeight <= 0 || right <= left || bottom <= top)
            return null;
        int minimumVisible = Math.max(6, dp(7, density));
        int effectiveLeft = Math.max(0, left);
        int effectiveRight = Math.min(screenWidth, right);
        int effectiveBottom = Math.min(screenHeight, bottom);

        // Do not pin any overlay to the top when a source has scrolled away.
        if (top < 0 || top >= screenHeight
                || effectiveBottom - top < minimumVisible
                || effectiveRight - effectiveLeft < dp(32, density))
            return null;

        return new Placement(effectiveLeft, top,
                effectiveRight - effectiveLeft, effectiveBottom - top);
    }

    private static int dp(int n, float density) {
        return Math.max(1, Math.round(n * density));
    }

    /** Just the source text rectangle, never header, avatar, or controls. */
    public static final class Placement {
        public final int x, y, width, height;

        Placement(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}
