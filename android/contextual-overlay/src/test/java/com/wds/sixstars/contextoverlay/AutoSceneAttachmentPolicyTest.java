package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pure JVM tests: device SDK and real X window are not required. */
public final class AutoSceneAttachmentPolicyTest {
    @Test public void onlyAndroid14OrNewerMayAttach() {
        assertFalse(AutoSceneAttachmentPolicy.supports(33, 5, 1080, 2300));
        assertTrue(AutoSceneAttachmentPolicy.supports(34, 5, 1080, 2300));
        assertTrue(AutoSceneAttachmentPolicy.supports(36, 5, 1080, 2300));
    }

    @Test public void refusesUnknownOrZeroSizedWindow() {
        assertFalse(AutoSceneAttachmentPolicy.supports(36, -1, 1080, 2300));
        assertFalse(AutoSceneAttachmentPolicy.supports(36, 3, 0, 2300));
        assertFalse(AutoSceneAttachmentPolicy.supports(36, 3, 1080, 0));
    }
}
