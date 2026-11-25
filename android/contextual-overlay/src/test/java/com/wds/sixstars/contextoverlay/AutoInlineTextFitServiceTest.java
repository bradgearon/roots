package com.wds.sixstars.contextoverlay;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoInlineTextFitServiceTest {
    @Test public void showsMoreWhenTranslationWouldOverflowPostTextArea() {
        assertTrue(AutoInlineTextFitService.exceedsVisibleSpace(140, 70, 7, 3));
        assertTrue(AutoInlineTextFitService.exceedsVisibleSpace(50, 70, 4, 3));
    }

    @Test public void hidesMoreForTranslationThatFits() {
        assertFalse(AutoInlineTextFitService.exceedsVisibleSpace(55, 70, 2, 3));
        assertFalse(AutoInlineTextFitService.exceedsVisibleSpace(70, 70, 3, 3));
    }
}
