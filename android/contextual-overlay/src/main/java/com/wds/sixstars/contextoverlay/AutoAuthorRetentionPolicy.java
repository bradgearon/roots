package com.wds.sixstars.contextoverlay;

/**
 * A brief missing author node does not mean a still-visible post has changed
 * identity. Retain only a previously VERIFIED name/anchor for the SAME body ID,
 * and never extrapolate without a fresh body rectangle.
 *
 * The bound is deliberately short; author data is not cached across pages.
 */
final class AutoAuthorRetentionPolicy {
    static final long MISSING_GRACE_MS = 1100L;

    private AutoAuthorRetentionPolicy() { }

    static boolean mayRetain(long firstMissingAt, long now,
            int bodyDeltaX, int bodyDeltaY,
            int screenWidth, int screenHeight) {
        return firstMissingAt > 0 && now >= firstMissingAt
                && now - firstMissingAt < MISSING_GRACE_MS
                && Math.abs((long) bodyDeltaX) < Math.max(16, screenWidth / 5)
                && Math.abs((long) bodyDeltaY) < Math.max(48, screenHeight * 3 / 4);
    }
}
