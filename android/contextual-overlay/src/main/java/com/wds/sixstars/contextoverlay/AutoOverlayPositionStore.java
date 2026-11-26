package com.wds.sixstars.contextoverlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Single source of truth for the AUTO scene's spatial state.
 *
 * A fresh accessibility snapshot is authoritative. Between snapshots, ONLY
 * an actual measured AccessibilityEvent scroll delta moves coordinates.
 * There is no extrapolation, velocity integration or invented animation.
 *
 * This class is pure Java: no View, WindowManager, model or accessibility API.
 * Its source IDs are in-memory text fingerprints, never written to disk.
 */
final class AutoOverlayPositionStore {
    static final long TRANSIENT_GAP_MS = 1100L;

    /** One immutable body/controls pair per post fingerprint. */
    static final class Observation {
        final String id;
        final AutoInlinePlacementService.Placement rectangle;
        final AutoDetailsPlacementService.Placement details;
        final AutoDetailsPlacementService.Placement nameDetails;
        final String authorName;

        Observation(String id, AutoInlinePlacementService.Placement rectangle) {
            this(id, rectangle, null);
        }

        Observation(String id, AutoInlinePlacementService.Placement rectangle,
                AutoDetailsPlacementService.Placement details) {
            this(id, rectangle, details, null, null);
        }

        Observation(String id, AutoInlinePlacementService.Placement rectangle,
                AutoDetailsPlacementService.Placement tweetDetails,
                AutoDetailsPlacementService.Placement nameDetails, String authorName) {
            this.id = id;
            this.rectangle = rectangle;
            this.details = tweetDetails;
            this.nameDetails = nameDetails;
            this.authorName = authorName;
        }
    }

    static final class Located {
        final String id;
        final AutoInlinePlacementService.Placement rectangle;
        final AutoDetailsPlacementService.Placement details;
        final AutoDetailsPlacementService.Placement nameDetails;
        final String authorName;

        Located(String id, AutoInlinePlacementService.Placement rectangle,
                AutoDetailsPlacementService.Placement details,
                AutoDetailsPlacementService.Placement nameDetails, String authorName) {
            this.id = id;
            this.rectangle = rectangle;
            this.details = details;
            this.nameDetails = nameDetails;
            this.authorName = authorName;
        }
    }

    private static final class Entry {
        final String id;
        AutoInlinePlacementService.Placement rectangle;
        AutoDetailsPlacementService.Placement details;
        AutoDetailsPlacementService.Placement nameDetails;
        String authorName;
        long missingSince;
        long authorMissingSince;

        Entry(String id, AutoInlinePlacementService.Placement rectangle,
                AutoDetailsPlacementService.Placement details,
                AutoDetailsPlacementService.Placement nameDetails, String authorName) {
            this.id = id;
            this.rectangle = rectangle;
            this.details = details;
            this.nameDetails = nameDetails;
            this.authorName = authorName;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final int screenWidth;
    private final int screenHeight;
    private final int safeTop;
    private boolean coordinatesTrusted = true;
    private long version;

    AutoOverlayPositionStore(int screenWidth, int screenHeight) {
        this(screenWidth, screenHeight, 0);
    }

    AutoOverlayPositionStore(int screenWidth, int screenHeight, int safeTop) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.safeTop = Math.max(0, safeTop);
    }

    long version() { return version; }

    void publish(List<Observation> fresh, boolean scrolling, long nowMs) {
        boolean changed = !coordinatesTrusted;
        Set<String> found = new HashSet<>();
        for (Observation observation : fresh) {
            if (observation == null || observation.id == null ||
                    observation.rectangle == null || !found.add(observation.id))
                continue;
            Entry existing = entries.get(observation.id);
            if (existing == null) {
                existing = new Entry(observation.id, observation.rectangle,
                        observation.details, observation.nameDetails,
                        observation.authorName);
                entries.put(observation.id, existing);
                changed = true;
            } else {
                AutoDetailsPlacementService.Placement nextName = observation.nameDetails;
                String nextAuthor = observation.authorName;

                if (nextName == null && existing.nameDetails != null
                        && existing.authorName != null) {
                    // X can temporarily omit the header/PFP during scrolling or
                    // virtual-tree recomposition while keeping the SAME post.
                    // Move the old verified anchor by the newly observed body
                    // displacement; never extrapolate from time or velocity.
                    long firstMissing = existing.authorMissingSince == 0L
                            ? Math.max(1L, nowMs) : existing.authorMissingSince;
                    int dx = observation.rectangle.x - existing.rectangle.x;
                    int dy = observation.rectangle.y - existing.rectangle.y;
                    if (AutoAuthorRetentionPolicy.mayRetain(firstMissing, nowMs,
                            dx, dy, screenWidth, screenHeight)) {
                        AutoDetailsPlacementService.Placement moved =
                                existing.nameDetails.shifted(dx, dy);
                        if (moved.y >= safeTop
                                && moved.y + moved.height <= screenHeight
                                && moved.x >= 0
                                && moved.x + moved.width <= screenWidth) {
                            nextName = moved;
                            nextAuthor = existing.authorName;
                            existing.authorMissingSince = firstMissing;
                        } else {
                            existing.authorMissingSince = 0L;
                        }
                    } else {
                        existing.authorMissingSince = 0L;
                    }
                } else {
                    // A freshly verified author restores full confidence.
                    existing.authorMissingSince = 0L;
                }

                if (!same(existing.rectangle, observation.rectangle)
                        || !sameDetails(existing.details, observation.details)
                        || !sameDetails(existing.nameDetails, nextName)
                        || !java.util.Objects.equals(existing.authorName, nextAuthor))
                    changed = true;
                existing.rectangle = observation.rectangle;
                existing.details = observation.details;
                existing.nameDetails = nextName;
                existing.authorName = nextAuthor;
                existing.missingSince = 0;
            }
        }

        // X sometimes omits a stationary post from one or two snapshots while
        // the reply composer opens. Preserve it briefly without jitter.
        // Actual scrolling removes departed posts immediately.
        for (String id : new ArrayList<>(entries.keySet())) {
            if (found.contains(id)) continue;
            Entry missing = entries.get(id);
            if (missing == null) continue;
            if (scrolling) {
                entries.remove(id);
                changed = true;
            } else if (missing.missingSince == 0L) {
                missing.missingSince = Math.max(1L, nowMs);
            } else if (nowMs - missing.missingSince >= TRANSIENT_GAP_MS) {
                entries.remove(id);
                changed = true;
            }
        }
        coordinatesTrusted = true;
        if (changed) version++;
    }

    /**
     * Accessibility reports positive scroll Y when X content moves up.
     * Apply the reported PIXEL displacement directly to all scene positions.
     */
    void applyMeasuredScroll(int deltaX, int deltaY) {
        if (deltaX == 0 && deltaY == 0) return;
        for (Entry entry : entries.values()) {
            AutoInlinePlacementService.Placement p = entry.rectangle;
            entry.rectangle = shifted(p, -deltaX, -deltaY);
            if (entry.details != null)
                entry.details = entry.details.shifted(-deltaX, -deltaY);
            if (entry.nameDetails != null)
                entry.nameDetails = entry.nameDetails.shifted(-deltaX, -deltaY);
        }
        coordinatesTrusted = true;
        version++;
    }

    /** When no reliable displacement is available, hide until a fresh scan. */
    void suspendUntilMeasured() {
        coordinatesTrusted = false;
        version++;
    }

    boolean isTrusted() { return coordinatesTrusted; }

    boolean contains(String id) {
        return coordinatesTrusted && entries.containsKey(id);
    }

    /** An already identified name can survive a short missing header sample. */
    boolean hasTrackedAuthor(String id, long nowMs) {
        Entry entry = entries.get(id);
        return entry != null && entry.authorName != null
                && entry.nameDetails != null
                && (entry.authorMissingSince == 0L
                    || nowMs - entry.authorMissingSince
                        < AutoAuthorRetentionPolicy.MISSING_GRACE_MS);
    }

    boolean hasPendingAuthorExpiry() {
        for (Entry entry : entries.values())
            if (entry.authorMissingSince > 0L) return true;
        return false;
    }

    /**
     * Return the earliest remaining expiry, not a fresh 1.1s timeout per
     * scan. Otherwise continuous accessibility events keep postponing the
     * cleanup of an already missing author/control indefinitely.
     */
    long nextExpiryDelayMs(long nowMs) {
        long expiryAt = Long.MAX_VALUE;
        for (Entry entry : entries.values()) {
            if (entry.missingSince > 0L)
                expiryAt = Math.min(expiryAt,
                        entry.missingSince + TRANSIENT_GAP_MS);
            if (entry.authorMissingSince > 0L)
                expiryAt = Math.min(expiryAt,
                        entry.authorMissingSince
                                + AutoAuthorRetentionPolicy.MISSING_GRACE_MS);
        }
        return expiryAt == Long.MAX_VALUE ? -1L
                : Math.max(1L, expiryAt - nowMs + 1L);
    }

    List<Located> snapshot(long nowMs) {
        if (!coordinatesTrusted) return Collections.emptyList();
        ArrayList<Located> current = new ArrayList<>();
        for (String id : new ArrayList<>(entries.keySet())) {
            Entry entry = entries.get(id);
            if (entry == null) continue;
            if (entry.authorMissingSince > 0L
                    && nowMs - entry.authorMissingSince
                        >= AutoAuthorRetentionPolicy.MISSING_GRACE_MS) {
                entry.nameDetails = null;
                entry.authorName = null;
                entry.authorMissingSince = 0L;
                version++;
            }
            if (entry.missingSince > 0 &&
                    nowMs - entry.missingSince >= TRANSIENT_GAP_MS) {
                entries.remove(id);
                continue;
            }
            AutoInlinePlacementService.Placement p = entry.rectangle;
            // Don't pin overlays to edges: if original text is off screen,
            // the translated text and touch controls disappear with it.
            if (p.y < safeTop || p.y >= screenHeight ||
                    p.x + p.width <= 0 || p.x >= screenWidth ||
                    p.y + p.height <= 0) continue;
            current.add(new Located(entry.id, p, entry.details,
                    entry.nameDetails, entry.authorName));
        }
        return Collections.unmodifiableList(current);
    }

    void clear() {
        entries.clear();
        coordinatesTrusted = false;
        version++;
    }

    private static boolean same(AutoInlinePlacementService.Placement a,
            AutoInlinePlacementService.Placement b) {
        return a != null && b != null && a.x == b.x && a.y == b.y
                && a.width == b.width && a.height == b.height;
    }

    private static boolean sameDetails(AutoDetailsPlacementService.Placement a,
            AutoDetailsPlacementService.Placement b) {
        return a == null ? b == null : a.sameAs(b);
    }

    private static AutoInlinePlacementService.Placement shifted(
            AutoInlinePlacementService.Placement p, int dx, int dy) {
        return new AutoInlinePlacementService.Placement(
                p.x + dx, p.y + dy, p.width, p.height);
    }
}
