package com.wds.sixstars.contextoverlay;

import android.graphics.Rect;
import android.graphics.Paint;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures only X's foreground accessibility nodes. The reader extracts likely
 * post bodies; XPostSpatialAnalyzer selects one body per card and handles
 * viewport geometry. No screenshot, persistence or non-X application reading.
 */
public final class XVisibleTextReader {
    public static final String X_PACKAGE = "com.twitter.android";
    private static final int MAX_VISITED_NODES = 850;
    private static final int MAX_DETAIL_NODES = 1400;
    private static final int MAX_SOURCE_CHARS = 1100;
    private final XPostSpatialAnalyzer spatial = new XPostSpatialAnalyzer();
    private int lastVisited;
    private int lastTextNodes;
    private int lastCandidateCount;
    private int lastAvatarCount;
    private int lastAuthorSampleCount;
    private int lastAuthorMatchCount;
    private int lastClippedNameCount;
    private boolean lastPostDetail;
    // In-memory discovery snapshot, independent of the chosen render layout.
    private List<TextCandidate> lastDiscovered = Collections.emptyList();
    public List<TextCandidate> lastDiscovered() { return lastDiscovered; }

    public int lastVisitedNodes() { return lastVisited; }
    public int lastTextNodes() { return lastTextNodes; }
    public int lastCandidateCount() { return lastCandidateCount; }
    public int lastAvatarCount() { return lastAvatarCount; }
    public int lastAuthorSampleCount() { return lastAuthorSampleCount; }
    public int lastAuthorMatchCount() { return lastAuthorMatchCount; }
    public int lastClippedNameCount() { return lastClippedNameCount; }
    public boolean lastPostDetail() { return lastPostDetail; }

    public List<TextCandidate> find(AccessibilityNodeInfo root, int width, int height) {
        return find(root, width, height,
                android.content.res.Resources.getSystem()
                        .getDisplayMetrics().scaledDensity);
    }

    public List<TextCandidate> find(AccessibilityNodeInfo root,
            int width, int height, float scaledDensity) {
        List<TextCandidate> raw = new ArrayList<>();
        List<Rect> avatars = new ArrayList<>();
        List<AuthorSample> authorSamples = new ArrayList<>();
        lastVisited = 0;
        lastTextNodes = 0;
        lastCandidateCount = 0;
        lastAvatarCount = 0;
        lastAuthorSampleCount = 0;
        lastAuthorMatchCount = 0;
        lastClippedNameCount = 0;
        lastPostDetail = false;
        lastDiscovered = Collections.emptyList();
        if (root == null || root.getPackageName() == null
                || !X_PACKAGE.contentEquals(root.getPackageName())) return raw;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        boolean detailScreen = false;

        while (!queue.isEmpty()
                && lastVisited < (detailScreen ? MAX_DETAIL_NODES : MAX_VISITED_NODES)) {
            AccessibilityNodeInfo node = queue.removeFirst();
            final int order = ++lastVisited;
            try {
                // Descendants of custom X containers can still be readable even
                // when the container itself is not considered visible.
                int childCount = Math.min(node.getChildCount(), 45);
                for (int i = 0; i < childCount; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.add(child);
                }
                if (!node.isVisibleToUser() || node.isEditable() || node.isPassword()) continue;
                if (node.getPackageName() != null && !X_PACKAGE.contentEquals(node.getPackageName()))
                    continue;

                // Avatar identity is a separate card element, not translatable
                // body text. Capture its rectangle without retaining X nodes.
                String nodeId = node.getViewIdResourceName();
                if (XPostDetailContextService.isDetailContainerId(nodeId))
                    detailScreen = true;
                CharSequence accessibilityLabel = node.getContentDescription();
                if (avatars.size() < 48) {
                    Rect avatar = new Rect();
                    node.getBoundsInScreen(avatar);
                    String imageClass = node.getClassName() == null ? ""
                            : node.getClassName().toString().toLowerCase(Locale.ROOT);
                    boolean namedAvatar = looksLikeAvatar(nodeId, accessibilityLabel);
                    // X sometimes omits avatar resource IDs/descriptions. A
                    // plausible square ImageView in the left PFP gutter is
                    // still usable, but only when matched to a post header.
                    boolean gutterImage = imageClass.contains("imageview")
                            && avatar.left >= 0 && avatar.right <= width / 4;
                    if ((namedAvatar || gutterImage)
                            && plausibleAvatar(avatar, width, height))
                        avatars.add(avatar);
                }

                CharSequence textValue = node.getText();
                boolean description = textValue == null || textValue.length() == 0;
                if (description) textValue = node.getContentDescription();
                if (textValue == null || textValue.length() == 0) continue;
                lastTextNodes++;

                String text = TextFingerprint.normalized(textValue.toString());
                if (text.length() > MAX_SOURCE_CHARS) continue;
                String viewId = node.getViewIdResourceName();
                String className = node.getClassName() == null
                        ? "" : node.getClassName().toString();
                String language = languageHint(text);

                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (XPostDetailContextService.isDetailHeading(
                        text, viewId, className, node.isClickable(),
                        node.isEditable(), bounds.top, bounds.bottom, height))
                    detailScreen = true;
                if (!XPostSpatialAnalyzer.onScreen(bounds, width, height)) continue;
                // X sometimes exposes the OPENED post on detail pages through
                // one localized container description rather than a TextView.
                // Keep timeline descriptions conservative to avoid translating
                // a whole feed or navigation labels.
                if (description && childCount > 5
                        && !(detailScreen && !language.isEmpty()
                            && text.length() <= 600
                            && bounds.height() < height / 2)) continue;

                boolean knownAuthor = XAuthorNameHeuristics.isNameId(viewId);
                if (authorSamples.size() < 160
                        && XAuthorNameHeuristics.mayBeAuthorLabel(
                                text, viewId, className, description)) {
                    // Author/handle are potential HEADER roles, not post text.
                    authorSamples.add(new AuthorSample(text, bounds, knownAuthor));
                }
                // In some X versions the direct TextView is ellipsized while
                // its contentDescription contains a richer name+handle line.
                // Keep that description as a possible header separately.
                CharSequence desc = node.getContentDescription();
                if (!description && desc != null && authorSamples.size() < 160) {
                    String label = TextFingerprint.normalized(desc.toString());
                    if (!label.equals(text) &&
                            XAuthorNameHeuristics.mayBeAuthorLabel(
                                    label, viewId, className, true))
                        authorSamples.add(new AuthorSample(label, bounds, knownAuthor));
                }
                // Author names are translated by their own role, not as bodies.
                if (knownAuthor) continue;

                int score = PostTextHeuristics.score(text, viewId, className, description,
                        node.isClickable(), bounds.width(), bounds.height(), language);
                if (score < 0) continue;

                PostCard card = findCard(node, bounds, width, height);
                // Prefer the body node exposed with a known tweet-text resource ID,
                // then bounded text within a known tweet card, then fallback text.
                if (card.found) score += 28;
                raw.add(new TextCandidate(text, language, bounds, card.bounds,
                        order, score, card.found, description,
                        PostTextHeuristics.isPostTextId(viewId)));
            } catch (IllegalStateException ignored) {
                // X may recompose a node while its virtual accessibility tree is read.
                // A later event performs a new scan without retaining stale nodes.
            }
        }

        // Associate each readable body with its nearest profile image. The
        // spatial analyzer may then deduplicate header/body descriptions of
        // the SAME tweet even when X omits recognizable post-container IDs.
        List<TextCandidate> associated = new ArrayList<>(raw.size());
        for (TextCandidate candidate : raw) {
            Rect avatar = nearestAvatar(candidate, avatars, width);
            XAuthorInfo author = findAuthor(candidate, avatar, authorSamples, width,
                    scaledDensity);
            associated.add(new TextCandidate(candidate.text, candidate.languageHint,
                    candidate.bounds, candidate.postBounds, candidate.order,
                    candidate.bodyScore, candidate.hasPostContainer,
                    candidate.fromDescription, candidate.knownPostTextId, avatar, author));
        }
        List<TextCandidate> selected =
                spatial.locate(associated, width, height, detailScreen);
        lastPostDetail = detailScreen;
        List<TextCandidate> authorNames = independentAuthors(authorSamples, avatars,
                selected, raw, width, scaledDensity);
        Map<String, TextCandidate> work = new LinkedHashMap<>();
        for (TextCandidate name : authorNames) work.put(name.fingerprint, name);
        for (TextCandidate body : selected) work.put(body.fingerprint, body);
        for (TextCandidate body : raw) {
            if (body.languageHint.isEmpty() || body.fromDescription
                    || !body.knownPostTextId && overlapsAuthor(body, authorNames))
                continue;
            boolean duplicateRegion = false;
            for (TextCandidate chosen : selected) {
                if (!body.fingerprint.equals(chosen.fingerprint)
                        && Rect.intersects(body.bounds, chosen.bounds)) {
                    duplicateRegion = true; break;
                }
            }
            if (!duplicateRegion && work.size() < 256)
                work.putIfAbsent(body.fingerprint, body);
        }
        lastDiscovered = Collections.unmodifiableList(new ArrayList<>(work.values()));
        List<TextCandidate> scene = new ArrayList<>(selected);
        scene.addAll(authorNames);
        lastCandidateCount = scene.size();
        lastAvatarCount = avatars.size();
        lastAuthorSampleCount = authorSamples.size();
        for (TextCandidate item : authorNames) {
            if (!item.avatarBounds.isEmpty()) lastAuthorMatchCount++;
            if (item.author.truncated) lastClippedNameCount++;
        }
        return scene;
    }

    private static boolean overlapsAuthor(TextCandidate body, List<TextCandidate> names) {
        for (TextCandidate name : names)
            if (Rect.intersects(body.bounds, name.bounds)) return true;
        return false;
    }

    /** A name can be discovered without a body. An ambiguous header requires
     * a matching avatar; strong IDs/composite headers may queue without one. */
    private static List<TextCandidate> independentAuthors(List<AuthorSample> samples,
            List<Rect> avatars, List<TextCandidate> selected, List<TextCandidate> raw,
            int width, float density) {
        Map<String, TextCandidate> names = new LinkedHashMap<>();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(18f * Math.max(1f, density));
        paint.setFakeBoldText(true);
        for (AuthorSample sample : samples) {
            if (XAuthorNameHeuristics.looksLikeHandle(sample.text)) continue;
            boolean composite = XAuthorNameHeuristics.looksCompositeHeader(sample.text);
            boolean body = false;
            for (TextCandidate candidate : raw)
                if (candidate.knownPostTextId && candidate.bounds.equals(sample.bounds)) {
                    body = true; break;
                }
            if (body) continue;
            Rect avatar = new Rect();
            int best = Integer.MAX_VALUE;
            for (Rect a : avatars) {
                int gap = sample.bounds.left - a.right;
                int dy = Math.abs(sample.bounds.top - a.top);
                if (gap < -Math.max(6, width/75) || gap > width/6
                        || sample.bounds.top < a.top-a.height()/3
                        || sample.bounds.top > a.top+a.height()/3
                        || sample.bounds.height() > a.height()*4/5) continue;
                int score = Math.abs(gap)+dy;
                if (score < best) { best=score; avatar=new Rect(a); }
            }
            if (avatar.isEmpty() && !sample.knownNameId && !composite) continue;
            String name = composite ? sample.text.substring(0,sample.text.indexOf('@')).trim() : sample.text;
            if (name.isEmpty()) continue;
            if (avatar.isEmpty() && sample.bounds.left >= width/7) {
                int size=Math.min(Math.max(36,width/10),width/5);
                int right=sample.bounds.left-Math.max(5,width/55);
                int top=Math.max(0,sample.bounds.top-size/12);
                if(right>=size) avatar=new Rect(right-size,top,right,top+size);
            }
            boolean clipped=XAuthorNameHeuristics.looksTruncated(sample.text,
                    paint.measureText(sample.text),sample.bounds.width());
            if (!sample.knownNameId && !composite) {
                boolean selectedBody=false;
                for(TextCandidate candidate:selected)
                    if(candidate.bounds.equals(sample.bounds) && candidate.text.equals(sample.text)){
                        selectedBody=true;break;
                    }
                if(selectedBody)continue;
            }
            for(AuthorSample other:samples) {
                if(other==sample || !XAuthorNameHeuristics.looksLikeHandle(other.text))continue;
                if(Math.abs(other.bounds.top-sample.bounds.top)<=Math.max(10,avatar.height()/2)
                        && other.bounds.left>=sample.bounds.left
                        && XAuthorNameHeuristics.hasVisibleEllipsis(other.text))clipped=true;
            }
            addAuthor(names,new XAuthorInfo(name,sample.bounds,avatar,clipped));
        }
        // Preserve verified associations as a fallback; they no longer gate
        // name discovery. Do not duplicate a richer independent header.
        for(TextCandidate candidate:selected) {
            if(candidate.author==null)continue;
            XAuthorInfo a=candidate.author;
            boolean already=false;
            for(TextCandidate name:names.values())
                if(Rect.intersects(name.bounds,a.nameBounds)){already=true;break;}
            if(!already)addAuthor(names,a);
        }
        return new ArrayList<>(names.values());
    }

    private static void addAuthor(Map<String, TextCandidate> names, XAuthorInfo info) {
        TextCandidate c=TextCandidate.forAuthor(info,names.size());
        TextCandidate existing=names.get(c.fingerprint);
        if(existing==null || existing.avatarBounds.isEmpty() && !c.avatarBounds.isEmpty()
                || !existing.author.truncated && info.truncated)
            names.put(c.fingerprint,c);
    }

    private static PostCard findCard(AccessibilityNodeInfo node, Rect body, int width, int height) {
        AccessibilityNodeInfo parent = node.getParent();
        for (int depth = 0; parent != null && depth < 9; depth++) {
            Rect rect = new Rect();
            parent.getBoundsInScreen(rect);
            if (rect.width() >= body.width() && rect.height() >= body.height()
                    && rect.width() <= width && rect.height() <= height * 2
                    && rect.right > 0 && rect.left < width) {
                String id = parent.getViewIdResourceName();
                if (PostTextHeuristics.isPostContainerId(id))
                    return new PostCard(rect, true);

                AccessibilityNodeInfo grand = parent.getParent();
                if (grand != null && grand.getClassName() != null
                        && PostTextHeuristics.isListClass(grand.getClassName().toString()))
                    return new PostCard(rect, true);
            }
            // Stop at the feed's scroll container rather than using the entire
            // timeline as a single post bounding box.
            if (parent.isScrollable()) break;
            parent = parent.getParent();
        }
        return new PostCard(body, false);
    }

    private static boolean looksLikeAvatar(String id, CharSequence description) {
        String resource = id == null ? "" : id.toLowerCase(Locale.ROOT);
        String label = description == null ? "" :
                description.toString().toLowerCase(Locale.ROOT);
        return resource.contains("avatar") || resource.contains("profile_image")
                || resource.contains("profile_photo")
                || label.startsWith("profile image") || label.startsWith("profile photo")
                || label.startsWith("profile picture") || label.startsWith("avatar ")
                || label.startsWith("profile images for");
    }

    private static boolean plausibleAvatar(Rect box, int width, int height) {
        int w = box.width();
        int h = box.height();
        return w >= Math.max(24, width / 32) && h >= Math.max(24, width / 32)
                && w <= width / 4 && h <= width / 4
                && Math.abs(w - h) <= Math.max(w, h) / 3
                && XPostSpatialAnalyzer.onScreen(box, width, height);
    }

    private static Rect nearestAvatar(TextCandidate body, List<Rect> avatars, int width) {
        Rect nearest = null;
        int best = Integer.MAX_VALUE;
        for (Rect avatar : avatars) {
            int gap = body.bounds.left - avatar.right;
            int verticalGap = body.bounds.top - avatar.bottom;
            int maxVerticalGap = Math.max(width / 5, avatar.height() * 2);
            if (gap < -Math.max(8, width / 65)
                    || gap > Math.max(60, width / 6)
                    || avatar.top > body.bounds.top + width / 24
                    || verticalGap > maxVerticalGap) continue;

            // X often exposes a body-only "post container". Reject an
            // unrelated avatar only when the returned card is demonstrably
            // a full-width container INCLUDING the avatar's gutter.
            if (body.hasPostContainer && body.postBounds.left <= avatar.left
                    && body.postBounds.right >= avatar.right
                    && (avatar.centerY() < body.postBounds.top
                        || avatar.centerY() > body.postBounds.bottom)) continue;

            int score = Math.max(0, gap) + Math.abs(verticalGap);
            if (score < best) {
                best = score;
                nearest = avatar;
            }
        }
        return nearest == null ? null : new Rect(nearest);
    }

    private static final class AuthorSample {
        final String text;
        final Rect bounds;
        final boolean knownNameId;

        AuthorSample(String text, Rect bounds, boolean knownNameId) {
            this.text = text;
            this.bounds = new Rect(bounds);
            this.knownNameId = knownNameId;
        }
    }

    /**
     * Bind one author to its body through the actual avatar/header geometry.
     * A conservative inferred avatar gutter is used only when X provides a
     * strong name ID or a compact name+handle header but hides its image node.
     */
    private static XAuthorInfo findAuthor(TextCandidate body, Rect foundAvatar,
            List<AuthorSample> samples, int screenWidth, float density) {
        AuthorSample best = null;
        int bestScore = Integer.MIN_VALUE;

        for (AuthorSample sample : samples) {
            if (sample.text.equals(body.text) ||
                    XAuthorNameHeuristics.looksLikeHandle(sample.text)) continue;
            boolean known = sample.knownNameId;
            boolean composite = XAuthorNameHeuristics.looksCompositeHeader(sample.text);
            boolean plausible;
            if (foundAvatar != null && !foundAvatar.isEmpty()) {
                plausible = XAuthorNameHeuristics.plausibleHeader(
                        sample.bounds.left, sample.bounds.top, sample.bounds.right,
                        sample.bounds.bottom, body.bounds.left, body.bounds.top,
                        foundAvatar.left, foundAvatar.top, foundAvatar.right,
                        foundAvatar.bottom, screenWidth, known);
            } else {
                plausible = XAuthorNameHeuristics.plausibleHeaderWithoutAvatar(
                        sample.bounds.left, sample.bounds.top, sample.bounds.right,
                        sample.bounds.bottom, body.bounds.left, body.bounds.top,
                        screenWidth, known, composite);
            }
            if (!plausible) continue;
            int score = (known ? 180 : 0) + (composite ? 60 : 0)
                    - Math.abs(sample.bounds.left - body.bounds.left) / 4
                    - Math.abs(sample.bounds.bottom - body.bounds.top) / 4;
            if (score > bestScore) {
                bestScore = score;
                best = sample;
            }
        }
        if (best == null) return null;

        Rect avatar = foundAvatar;
        if (avatar == null || avatar.isEmpty()) {
            // The inferred PFP sits in X's measured LEFT GUTTER, not on the
            // author row. Never synthesize one without a confirmed header.
            int size = Math.min(Math.max(36, screenWidth / 10), screenWidth / 5);
            int gap = Math.max(5, screenWidth / 55);
            int right = best.bounds.left - gap;
            int top = Math.max(0, best.bounds.top - size / 12);
            avatar = new Rect(Math.max(0, right - size), top, right, top + size);
            if (avatar.width() < size * 3 / 4) return null;
        }

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(18f * Math.max(1f, density));
        paint.setFakeBoldText(true);
        boolean clipped = XAuthorNameHeuristics.looksTruncated(best.text,
                paint.measureText(best.text), best.bounds.width());

        // X can truncate the @handle even if the display name fits. The
        // badge is useful in either case; keep the actual available name.
        for (AuthorSample other : samples) {
            if (other == best || other.text.equals(body.text)) continue;
            boolean isHandle = XAuthorNameHeuristics.looksLikeHandle(other.text);
            boolean composite = XAuthorNameHeuristics.looksCompositeHeader(other.text);
            if (!isHandle && !composite) continue;
            if (Math.abs(other.bounds.top - best.bounds.top) >
                    Math.max(28, avatar.height() / 2)) continue;
            if (other.bounds.left < best.bounds.left ||
                    other.bounds.left > screenWidth) continue;
            if (XAuthorNameHeuristics.hasVisibleEllipsis(other.text))
                clipped = true;
        }

        return new XAuthorInfo(best.text, best.bounds, avatar, clipped);
    }

    private static final class PostCard {
        final Rect bounds;
        final boolean found;
        PostCard(Rect bounds, boolean found) {
            this.bounds = new Rect(bounds);
            this.found = found;
        }
    }

    /**
     * Best-effort language hint. Actual translation stays with PLaMo. Native X
     * doesn't necessarily expose the tweet's language or normalized post ID.
     */
    public static String languageHint(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.codePoints().anyMatch(c -> (c >= 0x3040 && c <= 0x30ff))) return "ja";
        if (text.codePoints().anyMatch(c -> c >= 0xac00 && c <= 0xd7af)) return "ko";
        if (text.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06ff)) return "ar";
        if (text.codePoints().anyMatch(c -> c >= 0x0400 && c <= 0x04ff)) return "ru";
        if (text.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)) return "zh";
        String s = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+", " ") + " ";
        if (containsAny(s, " bonjour ", " merci ", " pourquoi ", " aujourd hui ", " je suis ", " avec vous ")) return "fr";
        if (containsAny(s, " hola ", " gracias ", " buenos dias ", " buenas noches ", " estoy ", " nosotros ")) return "es";
        if (containsAny(s, " hallo ", " danke ", " bitte ", " guten morgen ", " ich bin ")) return "de";
        return "";
    }

    private static boolean containsAny(String text, String... words) {
        for (String candidate : words) if (text.contains(candidate)) return true;
        return false;
    }
}
