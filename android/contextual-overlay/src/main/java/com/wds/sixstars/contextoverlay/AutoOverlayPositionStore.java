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
        final boolean nameInlineSafe,nameClipped;

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
            this(id,rectangle,tweetDetails,nameDetails,authorName,true,false);
        }

        Observation(String id, AutoInlinePlacementService.Placement rectangle,
                AutoDetailsPlacementService.Placement tweetDetails,
                AutoDetailsPlacementService.Placement nameDetails, String authorName,
                boolean nameInlineSafe,boolean nameClipped) {
            this.nameInlineSafe=nameInlineSafe;
            this.nameClipped=nameClipped;
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
        final boolean nameInlineSafe,nameClipped;

        Located(String id, AutoInlinePlacementService.Placement rectangle,
                AutoDetailsPlacementService.Placement details,
                AutoDetailsPlacementService.Placement nameDetails, String authorName,
                boolean nameInlineSafe,boolean nameClipped) {
            this.nameInlineSafe=nameInlineSafe;
            this.nameClipped=nameClipped;
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
        boolean nameInlineSafe,nameClipped;
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
    private long version, occurrenceSequence;

    AutoOverlayPositionStore(int screenWidth, int screenHeight) {
        this(screenWidth, screenHeight, 0);
    }

    AutoOverlayPositionStore(int screenWidth, int screenHeight, int safeTop) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.safeTop = Math.max(0, safeTop);
    }

    long version() { return version; }

    // A content fingerprint identifies a translation, not an occurrence.
    // Two visible copies of the same text must own separate positions.
    static String contentId(String occurrence){
        int separator=occurrence.lastIndexOf('@');
        return separator<0?occurrence:occurrence.substring(0,separator);
    }
    boolean containsContent(String content){
        if(!coordinatesTrusted)return false;
        for(Entry e:entries.values())if(contentId(e.id).equals(content))return true;
        return false;
    }
    void publishScene(List<Observation> fresh,boolean scrolling,long nowMs){
        List<Observation> assigned=new ArrayList<>();
        Set<String> used=new HashSet<>();
        for(Observation o:fresh){
            if(o==null||o.id==null||o.rectangle==null)continue;
            boolean duplicate=false;
            for(Observation a:assigned)if(contentId(a.id).equals(o.id)&&same(a.rectangle,o.rectangle)){
                duplicate=true;break;
            }
            if(duplicate)continue;
            Entry exact=null, overlapping=null;
            int overlaps=0;
            if(coordinatesTrusted)for(Entry e:entries.values()){
                if(used.contains(e.id)||!contentId(e.id).equals(o.id))continue;
                if(same(e.rectangle,o.rectangle)){exact=e;break;}
                if(overlaps(e.rectangle,o.rectangle)){overlapping=e;overlaps++;}
            }
            // Only reuse an occurrence when the measured rectangles agree
            // or overlap unambiguously. No cross-post text-only association.
            Entry matched=exact!=null?exact:overlaps==1?overlapping:null;
            String id=matched==null?o.id+"@"+(++occurrenceSequence):matched.id;
            used.add(id);
            assigned.add(new Observation(id,o.rectangle,o.details,o.nameDetails,
                o.authorName,o.nameInlineSafe,o.nameClipped));
        }
        // The auto scene never keeps an absent text rectangle. Translation
        // results stay cached independently and can be displayed on return.
        publish(assigned,true,nowMs);
    }

    void publish(List<Observation> fresh, boolean scrolling, long nowMs) {
        final boolean recovering = !coordinatesTrusted;
        boolean changed = recovering;
        boolean layoutMoved = false;
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
                existing.nameInlineSafe=observation.nameInlineSafe;
                existing.nameClipped=observation.nameClipped;
                entries.put(observation.id, existing);
                changed = true;
            } else {
                if(!same(existing.rectangle,observation.rectangle))layoutMoved=true;
                AutoDetailsPlacementService.Placement nextName = observation.nameDetails;
                String nextAuthor = observation.authorName;

                if (!recovering && nextName == null && existing.nameDetails != null
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
                        || !java.util.Objects.equals(existing.authorName, nextAuthor)
                        || existing.nameInlineSafe!=observation.nameInlineSafe
                        || existing.nameClipped!=observation.nameClipped)
                    changed = true;
                existing.rectangle = observation.rectangle;
                existing.details = observation.details;
                existing.nameDetails = nextName;
                existing.authorName = nextAuthor;
                existing.nameInlineSafe=observation.nameInlineSafe;
                existing.nameClipped=observation.nameClipped;
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
            // No grace period for an old post if the current snapshot
            // proves that layout moved or another text occupies its bounds.
            boolean replaced=false;
            for(Observation current:fresh)if(current!=null&&current.rectangle!=null&&overlaps(missing.rectangle,current.rectangle)){
                replaced=true;break;
            }
            if (scrolling || recovering || layoutMoved || replaced) {
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
        if (!coordinatesTrusted || (deltaX == 0 && deltaY == 0)) return;
        for (Entry entry : entries.values()) {
            AutoInlinePlacementService.Placement p = entry.rectangle;
            entry.rectangle = shifted(p, -deltaX, -deltaY);
            if (entry.details != null)
                entry.details = entry.details.shifted(-deltaX, -deltaY);
            if (entry.nameDetails != null)
                entry.nameDetails = entry.nameDetails.shifted(-deltaX, -deltaY);
        }
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
                    entry.nameDetails, entry.authorName,entry.nameInlineSafe,entry.nameClipped));
        }
        return Collections.unmodifiableList(current);
    }

    void clear() {
        entries.clear();
        coordinatesTrusted = false;
        version++;
    }

    private static boolean overlaps(AutoInlinePlacementService.Placement a,AutoInlinePlacementService.Placement b){
        return a.x<b.x+b.width&&b.x<a.x+a.width&&a.y<b.y+b.height&&b.y<a.y+a.height;
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
