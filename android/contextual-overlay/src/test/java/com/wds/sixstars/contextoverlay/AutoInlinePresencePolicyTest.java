package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoInlinePresencePolicyTest {
    @Test public void retainsPostThroughTransientReplyComposerRecomposition() {
        assertTrue(AutoInlinePresencePolicy.retainAbsent(1000, 1120, false));
        assertTrue(AutoInlinePresencePolicy.retainAbsent(1000,
                1000 + AutoInlinePresencePolicy.TRANSIENT_GAP_MS - 1, false));
    }

    @Test public void removesAtDeadlineAndOnRealScrolling() {
        assertFalse(AutoInlinePresencePolicy.retainAbsent(1000,
                1000 + AutoInlinePresencePolicy.TRANSIENT_GAP_MS, false));
        assertFalse(AutoInlinePresencePolicy.retainAbsent(1000, 1020, true));
        assertFalse(AutoInlinePresencePolicy.retainAbsent(0, 1050, false));
    }
}
