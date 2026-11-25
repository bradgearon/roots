package com.wds.sixstars.contextoverlay;

/**
 * Extracts only actual reported pixel movement. Android 28+ supplies a direct
 * scrollDeltaY; on older/uncooperative sources an absolute scrollY difference
 * from the SAME X window/source can also be an exact measurement.
 *
 * No gesture velocity, elapsed-time integration, animation curves, estimates,
 * or fabricated scroll amount. A result with measured=false means "hide stale
 * positions and wait for a new accessibility snapshot."
 */
final class XMeasuredScrollTracker {
    static final class Result {
        final boolean measured;
        final int deltaY;

        Result(boolean measured, int deltaY) {
            this.measured = measured;
            this.deltaY = deltaY;
        }
    }

    private String previousSource;
    private int previousAbsoluteY = -1;

    Result observe(String sourceKey, int directDeltaY, int absoluteScrollY) {
        boolean sameSource = sourceKey != null && sourceKey.equals(previousSource);
        boolean validAbsolute = absoluteScrollY >= 0;
        int delta = directDeltaY;

        boolean absoluteMeasured = sameSource && validAbsolute &&
                previousAbsoluteY >= 0;
        if (delta == 0 && absoluteMeasured)
            delta = absoluteScrollY - previousAbsoluteY;

        previousSource = sourceKey;
        previousAbsoluteY = validAbsolute ? absoluteScrollY : -1;

        // A valid matching absolute offset with zero change is also a
        // measurement: no movement occurred, so don't blink the overlay.
        if (delta != 0 || absoluteMeasured)
            return new Result(true, delta);
        return new Result(false, 0);
    }

    void reset() {
        previousSource = null;
        previousAbsoluteY = -1;
    }
}
