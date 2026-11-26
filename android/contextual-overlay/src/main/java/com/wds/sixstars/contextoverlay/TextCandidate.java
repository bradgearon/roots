package com.wds.sixstars.contextoverlay;

import android.graphics.Rect;

/** An immutable screen snapshot. Never retain an AccessibilityNodeInfo after a scan. */
public final class TextCandidate {
    public final String fingerprint;
    public final boolean authorName;
    public final String text;
    public final String languageHint;
    /** Bounds of just the selected post body, not the profile/header/actions. */
    public final Rect bounds;
    /** Best-effort bounds of the containing tweet card, if X exposes them. */
    public final Rect postBounds;
    /** Best-effort avatar geometry, kept separate from header and body text. */
    public final Rect avatarBounds;
    /** Independent author header, never itself translated as the post body. */
    public final XAuthorInfo author;
    public final int order;
    public final int bodyScore;
    public final boolean hasPostContainer;
    /** True only for direct node text, never image/profile descriptions. */
    public final boolean fromDescription;
    /** Explicit X post-text view identifier (stronger than card heuristics). */
    public final boolean knownPostTextId;

    TextCandidate(String text, String languageHint, Rect bounds, int order) {
        this(text, languageHint, bounds, bounds, order, 0, false);
    }

    TextCandidate(String text, String languageHint, Rect bounds, Rect postBounds, int order) {
        this(text, languageHint, bounds, postBounds, order, 0, false);
    }

    TextCandidate(String text, String languageHint, Rect bounds, Rect postBounds,
                  int order, int bodyScore, boolean hasPostContainer) {
        this(text, languageHint, bounds, postBounds, order, bodyScore,
                hasPostContainer, false, false);
    }

    TextCandidate(String text, String languageHint, Rect bounds, Rect postBounds,
                  int order, int bodyScore, boolean hasPostContainer,
                  boolean fromDescription, boolean knownPostTextId) {
        this(text, languageHint, bounds, postBounds, order, bodyScore,
                hasPostContainer, fromDescription, knownPostTextId, null);
    }

    TextCandidate(String text, String languageHint, Rect bounds, Rect postBounds,
                  int order, int bodyScore, boolean hasPostContainer,
                  boolean fromDescription, boolean knownPostTextId, Rect avatarBounds) {
        this(text, languageHint, bounds, postBounds, order, bodyScore,
                hasPostContainer, fromDescription, knownPostTextId, avatarBounds, null);
    }

    TextCandidate(String text, String languageHint, Rect bounds, Rect postBounds,
                  int order, int bodyScore, boolean hasPostContainer,
                  boolean fromDescription, boolean knownPostTextId, Rect avatarBounds,
                  XAuthorInfo author) {
        this(text, languageHint, bounds, postBounds, order, bodyScore,
                hasPostContainer, fromDescription, knownPostTextId, avatarBounds, author, false);
    }

    static TextCandidate forAuthor(XAuthorInfo author, int order) {
        return new TextCandidate(author.displayName,
                XVisibleTextReader.languageHint(author.displayName), author.nameBounds,
                author.nameBounds, order, 0, false, false, false,
                author.avatarBounds, author, true);
    }

    private TextCandidate(String text, String languageHint, Rect bounds, Rect postBounds,
                  int order, int bodyScore, boolean hasPostContainer,
                  boolean fromDescription, boolean knownPostTextId, Rect avatarBounds,
                  XAuthorInfo author, boolean authorName) {
        this.authorName = authorName;
        this.fingerprint = (authorName ? "name:" : "") + TextFingerprint.of(text);
        this.text = text;
        this.languageHint = languageHint;
        this.bounds = new Rect(bounds);
        this.postBounds = new Rect(postBounds);
        this.avatarBounds = avatarBounds == null ? new Rect() : new Rect(avatarBounds);
        this.author = author;
        this.order = order;
        this.bodyScore = bodyScore;
        this.hasPostContainer = hasPostContainer;
        this.fromDescription = fromDescription;
        this.knownPostTextId = knownPostTextId;
    }
}
