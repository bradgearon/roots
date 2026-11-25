package com.wds.sixstars.contextoverlay;

import java.util.Locale;

/**
 * Auto-mode candidate gate. Reuses the SAME candidates and language hints as
 * the previously working scrolling translator; does not require X-specific
 * view IDs or a known RecyclerView/post container (many X versions omit them).
 *
 * Only obvious accessibility UI/media labels are excluded. Post detection,
 * text extraction and ranking remain in XVisibleTextReader/XPostSpatialAnalyzer.
 */
public final class AutoTranslationEligibilityService {
    private AutoTranslationEligibilityService() { }

    public static boolean accepts(TextCandidate candidate, String targetLanguage) {
        return candidate != null && accepts(candidate.knownPostTextId,
                candidate.hasPostContainer, candidate.fromDescription,
                candidate.bodyScore, candidate.languageHint, targetLanguage,
                candidate.text);
    }

    static boolean accepts(boolean recognizedBody, boolean hasPostContainer,
            boolean fromDescription, int bodyScore, String source,
            String target, String text) {
        // Keep the old successful automatic selection behavior: a visible
        // non-target-language text candidate is eligible regardless of whether
        // X exposed a stable view ID or a recognizable post container.
        if (text == null || text.trim().length() < 3 || text.length() > 1100)
            return false;
        if (source == null || source.isEmpty() || target == null ||
                target.isEmpty() || source.equalsIgnoreCase(target))
            return false;

        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return !(normalized.startsWith("profile image") ||
                normalized.startsWith("profile images") ||
                normalized.startsWith("profile photo") ||
                normalized.startsWith("profile picture") ||
                normalized.startsWith("images for ") ||
                normalized.startsWith("image for ") ||
                normalized.startsWith("photo of ") ||
                normalized.startsWith("image description") ||
                normalized.startsWith("avatar of ") ||
                normalized.startsWith("view profile") ||
                normalized.startsWith("open profile") ||
                normalized.startsWith("reposted by ") ||
                normalized.startsWith("replying to ") ||
                normalized.startsWith("show this thread") ||
                normalized.startsWith("search x") ||
                normalized.startsWith("search twitter"));
    }
}
