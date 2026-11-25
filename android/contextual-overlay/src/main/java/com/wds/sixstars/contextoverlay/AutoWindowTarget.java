package com.wds.sixstars.contextoverlay;

import android.graphics.Rect;

/** Verified topmost X TYPE_APPLICATION window; never target an arbitrary app. */
final class AutoWindowTarget {
    final int windowId;
    final Rect screenBounds;

    AutoWindowTarget(int windowId, Rect bounds) {
        this.windowId = windowId;
        this.screenBounds = new Rect(bounds);
    }

    boolean isValid() {
        return windowId >= 0 && screenBounds.width() > 0 && screenBounds.height() > 0;
    }

    boolean sameSurface(AutoWindowTarget other) {
        return other != null && windowId == other.windowId
                && screenBounds.width() == other.screenBounds.width()
                && screenBounds.height() == other.screenBounds.height();
    }

    boolean samePosition(AutoWindowTarget other) {
        return sameSurface(other)
                && screenBounds.left == other.screenBounds.left
                && screenBounds.top == other.screenBounds.top;
    }
}
