package com.wds.sixstars.contextoverlay;

/**
 * Inline view lifetime policy: transient X accessibility-tree gaps should not
 * destroy and recreate translated text while a post is stationary.
 *
 * A real scroll departure removes missing posts immediately. For content-tree
 * recompositions without a scroll event, retain for a brief bounded grace.
 * All positions are still owned by the spatial analyzer.
 */
public final class AutoInlinePresencePolicy {
    public static final long TRANSIENT_GAP_MS = 1100L;

    private AutoInlinePresencePolicy() { }

    public static boolean retainAbsent(long missingSinceMs, long nowMs, boolean scrolling) {
        return !scrolling && missingSinceMs > 0
                && nowMs >= missingSinceMs
                && nowMs - missingSinceMs < TRANSIENT_GAP_MS;
    }
}
