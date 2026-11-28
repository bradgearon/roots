package com.wds.sixstars.contextoverlay;

/** Deliberately narrow: only Android 14+ and a verified X application window. */
final class AutoSceneAttachmentPolicy {
    private AutoSceneAttachmentPolicy() { }

    static boolean supports(int sdk, int windowId, int width, int height) {
        return sdk >= 34 && windowId >= 0 && width > 0 && height > 0;
    }

    static boolean canAttach(int sdk, AutoWindowTarget target) {
        return target != null && target.hasVerifiedOrigin() && supports(sdk, target.windowId,
                target.screenBounds.width(), target.screenBounds.height());
    }
}
