package com.wds.sixstars.contextoverlay;

import android.graphics.Rect;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reacts to changes in the spatial analyzer's immutable post layout.
 * Tracks visible post identities and body/card bounds, then applies only
 * real arrivals, departures, movements or mode switches to the renderer.
 *
 * No direct X access, translation, storage, or network calls belong here.
 */
public final class OverlayChangeReactor {
    private final AccessibilityOverlayRenderer renderer;
    private final Map<String, PostPosition> previous = new HashMap<>();
    private boolean previousAutoMode;
    private boolean hasSnapshot;

    public OverlayChangeReactor(AccessibilityOverlayRenderer renderer) {
        this.renderer = renderer;
    }

    public void observe(List<TextCandidate> candidates, boolean automatic,
                        AccessibilityOverlayRenderer.ClickHandler clickHandler) {
        observe(candidates, automatic, clickHandler, false);
    }

    public void observe(List<TextCandidate> candidates, boolean automatic,
                        AccessibilityOverlayRenderer.ClickHandler clickHandler,
                        boolean scrolling) {
        Map<String, PostPosition> next = new HashMap<>();
        for (TextCandidate c : candidates) {
            next.put(c.fingerprint, new PostPosition(c.bounds, c.postBounds,
                    c.avatarBounds, c.author));
        }
        if (!hasSnapshot || previousAutoMode != automatic ||
                !same(previous, next) || automatic && scrolling) {
            renderer.update(candidates, clickHandler, automatic, scrolling);
            previous.clear();
            previous.putAll(next);
            previousAutoMode = automatic;
            hasSnapshot = true;
        }
    }

    public boolean isPresent(String fingerprint) {
        return previous.containsKey(fingerprint);
    }

    public void prepared(String fingerprint) {
        if (!previousAutoMode && isPresent(fingerprint)) renderer.markReady(fingerprint);
    }

    public void translated(TextCandidate candidate, String text) {
        if (previousAutoMode && isPresent(candidate.fingerprint))
            renderer.showAutoTranslation(candidate, text);
    }

    public void clear() {
        previous.clear();
        hasSnapshot = false;
        renderer.clear(); // dismiss all badges and translation panels immediately
    }

    private static boolean same(Map<String, PostPosition> left, Map<String, PostPosition> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        for (String key : left.keySet()) {
            if (!left.get(key).sameAs(right.get(key))) return false;
        }
        return true;
    }

    private static final class PostPosition {
        final Rect text;
        final Rect post;
        final Rect avatar;
        final Rect name;
        final String authorName;
        final boolean authorClipped;

        PostPosition(Rect text, Rect post, Rect avatar, XAuthorInfo author) {
            this.text = new Rect(text);
            this.post = new Rect(post);
            this.avatar = new Rect(avatar);
            this.name = author == null ? new Rect() : new Rect(author.nameBounds);
            this.authorName = author == null ? "" : author.displayName;
            this.authorClipped = author != null && author.truncated;
        }

        boolean sameAs(PostPosition other) {
            return other != null && text.equals(other.text)
                    && post.equals(other.post) && avatar.equals(other.avatar)
                    && name.equals(other.name) && authorClipped == other.authorClipped
                    && authorName.equals(other.authorName);
        }
    }
}
