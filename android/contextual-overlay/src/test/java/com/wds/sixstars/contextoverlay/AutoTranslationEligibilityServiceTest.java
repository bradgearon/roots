package com.wds.sixstars.contextoverlay;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Keeps previously-working Auto candidates while rejecting obvious image/metadata labels. */
public class AutoTranslationEligibilityServiceTest {
    @Test public void acceptsExplicitJapanesePostBody() {
        assertTrue(AutoTranslationEligibilityService.accepts(
                true, false, false, 210, "ja", "en", "今日はよい天気です"));
    }

    @Test public void acceptsRealTextInsideKnownPostCard() {
        assertTrue(AutoTranslationEligibilityService.accepts(
                false, true, false, 125, "ja", "en", "日本語の投稿です"));
    }

    @Test public void ignoresDescriptionEvenWhenItContainsForeignCharacters() {
        assertFalse(AutoTranslationEligibilityService.accepts(
                true, true, true, 300, "ja", "en",
                "Profile images for 黒猫"));
    }

    @Test public void ignoresProfileImageLabelsButKeepsValidUnidentifiedPostText() {
        assertFalse(AutoTranslationEligibilityService.accepts(
                false, true, false, 135, "ja", "en",
                "Profile images for 黒猫"));
        // X often omits post IDs and card IDs, but these text candidates worked
        // in the earlier automatic translation overlay and must stay eligible.
        assertTrue(AutoTranslationEligibilityService.accepts(
                false, false, false, 62, "ja", "en",
                "Some unmatched Japanese string 日本語"));
        assertTrue(AutoTranslationEligibilityService.accepts(
                false, false, true, 56, "ja", "en",
                "こんにちは世界"));
    }

    @Test public void ignoresTargetLanguageButNotLowScoreForeignText() {
        assertFalse(AutoTranslationEligibilityService.accepts(
                true, true, false, 230, "en", "en", "English post"));
        assertTrue(AutoTranslationEligibilityService.accepts(
                false, true, false, 50, "ja", "en", "こんにちは"));
    }
    @Test public void englishToFrenchAndUnknownRealBodies() {
        assertTrue(AutoTranslationEligibilityService.accepts(true,false,false,210,"en","fr","The original post."));
        assertFalse(AutoTranslationEligibilityService.accepts(true,false,false,210,"fr","fr","Bonjour à tous."));
        assertTrue(AutoTranslationEligibilityService.accepts(true,false,false,210,"","fr","Great!"));
        assertTrue(AutoTranslationEligibilityService.accepts(false,true,false,68,"","fr","Great!"));
        assertFalse(AutoTranslationEligibilityService.accepts(false,false,true,70,"","fr","Unidentified description"));
        assertFalse(AutoTranslationEligibilityService.accepts(false,false,false,40,"","fr","Unknown"));
    }
}
