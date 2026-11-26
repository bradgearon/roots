package com.wds.sixstars.contextoverlay;

import android.graphics.Rect;

/**
 * Author/header data is never a translation candidate. A post body references
 * this separate role only when X exposes a plausible corresponding name.
 */
final class XAuthorInfo {
    final String displayName;
    final Rect nameBounds;
    final Rect avatarBounds;
    final boolean truncated;
    final boolean inlineSafe;

    XAuthorInfo(String name, Rect nameBounds, Rect avatarBounds, boolean truncated) {
        this(name,nameBounds,avatarBounds,truncated,true);
    }
    XAuthorInfo(String name, Rect nameBounds, Rect avatarBounds, boolean truncated, boolean inlineSafe) {
        this.inlineSafe=inlineSafe;
        this.displayName = name;
        this.nameBounds = new Rect(nameBounds);
        this.avatarBounds = new Rect(avatarBounds);
        this.truncated = truncated;
    }
}
