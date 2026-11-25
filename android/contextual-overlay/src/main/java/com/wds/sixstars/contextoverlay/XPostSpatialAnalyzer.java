package com.wds.sixstars.contextoverlay;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central, deterministic layout stage. Receives immutable post-body candidates
 * from XVisibleTextReader and resolves one body per post card, clipped to the
 * screen viewport. It has no WindowManager, network or translation dependencies.
 */
public final class XPostSpatialAnalyzer {
    private static final int MAX_VISIBLE_POSTS = 5;
    private static final int EDGE_MARGIN_PX = 36;

    public List<TextCandidate> locate(List<TextCandidate> raw, int width, int height) {
        Map<String, TextCandidate> best = new HashMap<>();
        for (TextCandidate candidate : raw) {
            if (!nearViewport(candidate.bounds, height, EDGE_MARGIN_PX)) continue;
            if (!nearViewport(candidate.postBounds, height, EDGE_MARGIN_PX)) continue;
            if (candidate.bounds.right <= 0 || candidate.bounds.left >= width) continue;

            // A post group is used only when accessibility exposes a distinct
            // card/list item. Generic text nodes must not merge adjacent tweets.
            String key = candidate.hasPostContainer
                    ? "post:" + candidate.postBounds.left + ":" + candidate.postBounds.top
                        + ":" + candidate.postBounds.right + ":" + candidate.postBounds.bottom
                    : !candidate.avatarBounds.isEmpty()
                        ? "avatar:" + candidate.avatarBounds.left + ":"
                            + candidate.avatarBounds.top + ":" + candidate.avatarBounds.right
                            + ":" + candidate.avatarBounds.bottom
                        : "body:" + candidate.fingerprint;
            TextCandidate previous = best.get(key);
            if (previous == null || betterBody(candidate, previous)) {
                best.put(key, candidate);
            }
        }

        ArrayList<TextCandidate> selected = new ArrayList<>(best.values());
        final int centerY = height / 2;
        selected.sort(Comparator
                .comparingInt((TextCandidate c) -> c.languageHint.isEmpty() ? 1 : 0)
                .thenComparingInt((TextCandidate c) -> c.knownPostTextId ? 0 : 1)
                .thenComparingInt(c -> c.fromDescription ? 1 : 0)
                .thenComparingInt((TextCandidate c) -> -c.bodyScore)
                .thenComparingInt(c -> Math.abs(c.bounds.centerY() - centerY))
                .thenComparingInt(c -> c.order));

        // Some X versions expose the same visible body in both a TextView and
        // a parent content description without a recognizable post ID. Dedup
        // strongly overlapping body rectangles instead of drawing two buttons.
        ArrayList<TextCandidate> distinct = new ArrayList<>();
        for (TextCandidate candidate : selected) {
            // Some X versions expose a whole post's header/contentDescription
            // as a second foreign-language candidate. Prefer its nearby real
            // TextView body, but keep the description when it is the ONLY
            // readable source in that card.
            if (candidate.fromDescription && shadowedByDirectBody(candidate, selected, width))
                continue;
            boolean overlapping = false;
            for (TextCandidate existing : distinct) {
                if (sameBodyRegion(existing.bounds, candidate.bounds)
                        || sameAvatar(existing, candidate)) {
                    overlapping = true;
                    break;
                }
            }
            if (!overlapping) distinct.add(candidate);
            if (distinct.size() == MAX_VISIBLE_POSTS) break;
        }
        return distinct;
    }

    private static boolean betterBody(TextCandidate candidate, TextCandidate previous) {
        // Explicit post text must win against profile image/long container labels,
        // even if a long content description scored higher by character count.
        if (candidate.knownPostTextId != previous.knownPostTextId)
            return candidate.knownPostTextId;
        if (candidate.fromDescription != previous.fromDescription)
            return !candidate.fromDescription;
        // Header and text may both be direct nodes on ID-less X builds.
        // For close scores, the body sits BELOW the name row beside the
        // same avatar; the name is never the translated surface.
        if (Math.abs(candidate.bodyScore - previous.bodyScore) <= 35
                && candidate.bounds.top != previous.bounds.top)
            return candidate.bounds.top > previous.bounds.top;
        return candidate.bodyScore > previous.bodyScore
                || (candidate.bodyScore == previous.bodyScore
                    && candidate.text.length() > previous.text.length());
    }

    private static boolean sameAvatar(TextCandidate first, TextCandidate second) {
        return !first.avatarBounds.isEmpty() && !second.avatarBounds.isEmpty()
                && first.avatarBounds.equals(second.avatarBounds);
    }

    private static boolean shadowedByDirectBody(TextCandidate description,
            List<TextCandidate> candidates, int screenWidth) {
        for (TextCandidate other : candidates) {
            if (other == description || other.fromDescription) continue;
            // Prefer explicit X body nodes or direct text very near the
            // description/header, but never merge distant tweets.
            if (Math.abs(other.bounds.top - description.bounds.top)
                    > Math.max(50, screenWidth / 8)) continue;
            if (other.bounds.left < description.bounds.left
                    || other.bounds.left > description.bounds.right) continue;
            if (other.knownPostTextId
                    || (!description.avatarBounds.isEmpty()
                        && description.avatarBounds.equals(other.avatarBounds))
                    || other.bounds.top > description.bounds.top)
                return true;
        }
        return false;
    }

    private static boolean sameBodyRegion(Rect first, Rect second) {
        Rect common = new Rect();
        if (!common.setIntersect(first, second)) return false;
        long intersection = (long) common.width() * common.height();
        long firstArea = (long) first.width() * first.height();
        long secondArea = (long) second.width() * second.height();
        return Math.min(firstArea, secondArea) > 0
                && intersection * 100 >= Math.min(firstArea, secondArea) * 78;
    }

    /** One source of truth for off-screen dismissal / async result validation. */
    public static boolean nearViewport(Rect bounds, int height, int marginPx) {
        if (bounds == null || bounds.isEmpty()) return false;
        return bounds.bottom >= -marginPx && bounds.top <= height + marginPx;
    }

    /** A translation panel is rendered only when the actual body intersects X. */
    public static boolean onScreen(Rect bounds, int width, int height) {
        return bounds != null && !bounds.isEmpty()
                && bounds.right > 0 && bounds.left < width
                && bounds.bottom > 0 && bounds.top < height;
    }
}
