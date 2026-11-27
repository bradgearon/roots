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
    private static final int MAX_VISIBLE_POSTS = 8;
    private static final int EDGE_MARGIN_PX = 36;

    public List<TextCandidate> locate(List<TextCandidate> raw, int width, int height) {
        return locate(raw, width, height, false);
    }

    public List<TextCandidate> locate(List<TextCandidate> raw, int width, int height,
            boolean detailScreen) {
        Map<String, TextCandidate> best = new HashMap<>();
        for (TextCandidate candidate : raw) {
            // X can expose the displayed author header as an ordinary TextView
            // without a stable resource ID. It is not a translatable post body.
            if (!candidate.avatarBounds.isEmpty()
                    && XAuthorNameHeuristics.likelyNameAtAvatar(
                        candidate.bounds.top, candidate.bounds.bottom,
                        candidate.avatarBounds.top, candidate.avatarBounds.bottom,
                        candidate.knownPostTextId)) continue;
            if (!nearViewport(candidate.bounds, height, EDGE_MARGIN_PX)) continue;
            if (!nearViewport(candidate.postBounds, height, EDGE_MARGIN_PX)) continue;
            if (candidate.bounds.right <= 0 || candidate.bounds.left >= width) continue;

            // A post group is used only when accessibility exposes a distinct
            // card/list item. Generic text nodes must not merge adjacent tweets.
            String key = groupKey(candidate,detailScreen);
            TextCandidate previous = best.get(key);
            if (previous == null || betterBody(candidate, previous)) {
                best.put(key, candidate);
            }
        }

        // Some X releases expose the same post body through several
        // accessibility nodes with different IDs. The best text candidate
        // may lack its author even though another node from that SAME card
        // had a verified name. Preserve the semantic author role.
        for (TextCandidate rawCandidate : raw) {
            if (rawCandidate.author == null) continue;
            String key = groupKey(rawCandidate,detailScreen);
            TextCandidate winner = best.get(key);
            if (winner == null || winner.author != null
                    || !(sameAvatar(winner, rawCandidate)
                            || winner.hasPostContainer
                                && winner.postBounds.equals(rawCandidate.postBounds)))
                continue;
            best.put(key, new TextCandidate(
                    winner.text, winner.languageHint, winner.bounds,
                    winner.postBounds, winner.order, winner.bodyScore,
                    winner.hasPostContainer, winner.fromDescription,
                    winner.knownPostTextId, winner.avatarBounds,
                    rawCandidate.author));
        }

        ArrayList<TextCandidate> selected = new ArrayList<>(best.values());
        final int centerY = height / 2;
        if (detailScreen) {
            // A post-detail page often contains dozens of replies and media.
            // The OPENED post is the earliest eligible body under the toolbar;
            // never let a lower reply win solely because its language/ID score
            // happens to be higher. A scrolling detail page may naturally
            // promote the topmost remaining visible reply after the source
            // post has actually scrolled off screen.
            selected.sort(Comparator
                    .comparingInt((TextCandidate c) -> c.bounds.top)
                    .thenComparingInt((TextCandidate c) -> c.fromDescription ? 1 : 0)
                    .thenComparingInt((TextCandidate c) -> -XPostDetailContextService.rank(
                            c.bounds.top, c.bounds.centerY(), c.bodyScore,
                            c.knownPostTextId, c.fromDescription, true, centerY))
                    .thenComparingInt(c -> c.order));
        } else {
            selected.sort(Comparator
                    .comparingInt((TextCandidate c) -> c.languageHint.isEmpty() ? 1 : 0)
                    .thenComparingInt((TextCandidate c) -> c.knownPostTextId ? 0 : 1)
                    .thenComparingInt(c -> c.fromDescription ? 1 : 0)
                    .thenComparingInt((TextCandidate c) -> -c.bodyScore)
                    .thenComparingInt(c -> Math.abs(c.bounds.centerY() - centerY))
                    .thenComparingInt(c -> c.order));
        }

        // Some X versions expose the same visible body in both a TextView and
        // a parent content description without a recognizable post ID. Dedup
        // strongly overlapping body rectangles instead of drawing two buttons.
        ArrayList<TextCandidate> distinct = new ArrayList<>();
        for (TextCandidate candidate : selected) {
            // Some X versions expose a whole post's header/contentDescription
            // as a second foreign-language candidate. Prefer its nearby real
            // TextView body, but keep the description when it is the ONLY
            // readable source in that card.
            if(!candidate.knownPostTextId && shadowedByKnownBody(candidate,selected))continue;
            if (candidate.fromDescription && shadowedByDirectBody(candidate, selected, width))
                continue;
            boolean overlapping = false;
            for (TextCandidate existing : distinct) {
                if (sameBodyRegion(existing.bounds, candidate.bounds)
                        || sameAvatar(existing, candidate)
                            && !separateBodies(existing,candidate,detailScreen)) {
                    overlapping = true;
                    break;
                }
            }
            if (!overlapping) distinct.add(candidate);
            if (distinct.size() == MAX_VISIBLE_POSTS) break;
        }
        // The selected text node can be different from the node exposing
        // the same card's author. Merge this independent semantic role at the
        // final dedup stage rather than losing it with the lower-ranked node.
        for (int index = 0; index < distinct.size(); index++) {
            TextCandidate winner = distinct.get(index);
            if (winner.author != null) continue;
            for (TextCandidate source : raw) {
                if (source.author == null || source == winner) continue;
                boolean matchingAvatar = sameAvatar(winner, source) && !separateBodies(winner,source,detailScreen);
                boolean matchingCard = winner.hasPostContainer
                        && source.hasPostContainer
                        && winner.postBounds.equals(source.postBounds) && !separateBodies(winner,source,detailScreen);
                boolean matchingText = winner.fingerprint.equals(source.fingerprint)
                        && sameBodyRegion(winner.bounds, source.bounds);
                if (!matchingAvatar && !matchingCard && !matchingText) continue;
                distinct.set(index, new TextCandidate(winner.text,
                        winner.languageHint, winner.bounds, winner.postBounds,
                        winner.order, winner.bodyScore, winner.hasPostContainer,
                        winner.fromDescription, winner.knownPostTextId,
                        winner.avatarBounds, source.author));
                break;
            }
        }
        return distinct;
    }

    private static boolean independentBody(TextCandidate candidate,boolean detailScreen) {
        return candidate.knownPostTextId ||
            detailScreen && !candidate.fromDescription && candidate.text.length() >= 12 && candidate.bodyScore >= 60;
    }

    private static boolean separateBodies(TextCandidate a,TextCandidate b,boolean detailScreen) {
        return independentBody(a,detailScreen) && independentBody(b,detailScreen)
                && !a.text.equals(b.text) && (a.bounds.bottom <= b.bounds.top || b.bounds.bottom <= a.bounds.top);
    }

    private static boolean shadowedByKnownBody(TextCandidate candidate,List<TextCandidate> candidates) {
        for(TextCandidate other:candidates)if(other.knownPostTextId && !other.fromDescription
                && sameBodyRegion(candidate.bounds,other.bounds))return true;
        return false;
    }

    private static String groupKey(TextCandidate candidate,boolean detailScreen) {
        // Distinct post bodies may share an outer conversation or reply container.
        if(independentBody(candidate,detailScreen))
            return "direct:"+candidate.fingerprint+":"+candidate.bounds.flattenToString();
        if (candidate.hasPostContainer) {
            return "post:" + candidate.postBounds.left + ":"
                    + candidate.postBounds.top + ":" + candidate.postBounds.right
                    + ":" + candidate.postBounds.bottom;
        }
        if (!candidate.avatarBounds.isEmpty()) {
            return "avatar:" + candidate.avatarBounds.left + ":"
                    + candidate.avatarBounds.top + ":" + candidate.avatarBounds.right
                    + ":" + candidate.avatarBounds.bottom;
        }
        return "body:" + candidate.fingerprint;
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
