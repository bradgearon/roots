package com.wds.sixstars.contextoverlay;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages translation requests and the in-memory cache. Spatial analysis is
 * owned by XVisibleTextReader/XPostSpatialAnalyzer. OverlayChangeReactor
 * handles post arrivals, moves, departures and mode changes.
 *
 * MANUAL: one ⇄ button per selected post, tap to translate/expand.
 * AUTO: use stricter post-body eligibility and replace the original text
 * area via AutoInlineTranslationRenderer. Manual remains unchanged.
 */
public final class OverlayTranslationCoordinator {
    public interface FreshView {
        List<TextCandidate> capture();
    }

    private static final long AUTO_RETRY_COOLDOWN_MS = 12000L;
    private final TranslationProvider provider;
    private final OverlayPreferences preferences;
    private final AccessibilityOverlayRenderer renderer;
    private final OverlayChangeReactor changeReactor;
    private final FreshView freshView;
    private final TranslationCache cache = new TranslationCache();
    private final Map<String, Long> retryAfter = new HashMap<>();
    // Latest immutable candidate list; network results must NEVER re-scan X.
    private List<TextCandidate> latestCandidates = Collections.emptyList();
    private final Handler main = new Handler(Looper.getMainLooper());

    private long requestSequence;
    private long automaticGeneration;
    private String autoPendingFingerprint;
    private long autoPendingMissingSince;
    private boolean active;

    public OverlayTranslationCoordinator(TranslationProvider provider,
            OverlayPreferences preferences,
            AccessibilityOverlayRenderer renderer,
            FreshView freshView) {
        this.provider = provider;
        this.preferences = preferences;
        this.renderer = renderer;
        this.changeReactor = new OverlayChangeReactor(renderer);
        this.freshView = freshView;
    }

    public void refresh(List<TextCandidate> current) {
        refresh(current, false);
    }

    public void refresh(List<TextCandidate> current, boolean scrolling) {
        latestCandidates = Collections.unmodifiableList(new ArrayList<>(current));
        active = preferences.enabled();
        if (!active) {
            clear();
            return;
        }

        boolean automatic = preferences.autoPreview();
        if (autoPendingFingerprint != null) {
            if (match(current, autoPendingFingerprint) != null) {
                autoPendingMissingSince = 0L;
            } else if (scrolling) {
                ++automaticGeneration;
                autoPendingFingerprint = null;
                autoPendingMissingSince = 0L;
            } else {
                long now = SystemClock.uptimeMillis();
                if (autoPendingMissingSince == 0L) {
                    autoPendingMissingSince = now;
                } else if (!AutoInlinePresencePolicy.retainAbsent(
                        autoPendingMissingSince, now, false)) {
                    ++automaticGeneration;
                    autoPendingFingerprint = null;
                    autoPendingMissingSince = 0L;
                }
            }
        }

        changeReactor.observe(current, automatic, this::translateAtTap, scrolling);
        String target = preferences.targetLanguage();
        for (TextCandidate candidate : current) {
            String existing = cache.read(candidate.text, target);
            if (existing == null) continue;
            if (automatic) {
                if (shouldTranslateAutomatically(candidate, target))
                    changeReactor.translated(candidate, existing);
            } else {
                changeReactor.prepared(candidate.fingerprint);
            }
        }

        // Only one auto request initiated at a time. A later scan may start
        // the next post; no timer/polling and no unbounded request queue.
        if (automatic && preferences.desktopTranslationEnabled()
                && autoPendingFingerprint == null) {
            for (TextCandidate candidate : current) {
                if (!shouldTranslateAutomatically(candidate, target)) continue;
                if (cache.read(candidate.text, target) != null) continue;
                String key = candidate.fingerprint + ":" + target;
                Long retry = retryAfter.get(key);
                if (retry != null && System.currentTimeMillis() < retry) continue;
                prefetch(candidate, target);
                break;
            }
        }
    }

    private static boolean shouldTranslateAutomatically(TextCandidate c, String target) {
        return AutoTranslationEligibilityService.accepts(c, target);
    }

    private void prefetch(TextCandidate candidate, String target) {
        final String fingerprint = candidate.fingerprint;
        final String retryKey = fingerprint + ":" + target;
        final long generation = ++automaticGeneration;
        autoPendingFingerprint = fingerprint;
        autoPendingMissingSince = 0L;

        provider.translate(candidate.text, candidate.languageHint, target,
                (translated, error) -> main.post(() -> {
                    if (generation != automaticGeneration) return;
                    autoPendingFingerprint = null;
                    autoPendingMissingSince = 0L;
                    if (!active || !preferences.enabled() ||
                            !preferences.desktopTranslationEnabled() ||
                            !preferences.autoPreview()) return;

                    if (error == null && translated != null && !translated.trim().isEmpty()) {
                        cache.write(candidate.text, target, translated);
                        retryAfter.remove(retryKey);
                    } else {
                        if (retryAfter.size() > 100) retryAfter.clear();
                        retryAfter.put(retryKey,
                                System.currentTimeMillis() + AUTO_RETRY_COOLDOWN_MS);
                    }
                    // Deliver through the latest published scene snapshot.
                    // NEVER perform an accessibility traversal from a PLaMo
                    // completion: the next Choreographer frame owns painting.
                    refresh(latestCandidates);
                }));
    }

    private void translateAtTap(TextCandidate tapped) {
        if (!active || !preferences.enabled() ||
                !preferences.desktopTranslationEnabled()) return;

        List<TextCandidate> fresh = freshView.capture();
        TextCandidate current = match(fresh, tapped.fingerprint);
        if (current == null) return;

        String target = preferences.targetLanguage();
        String cached = cache.read(current.text, target);
        if (cached != null) {
            renderer.showDetail(current, cached);
            return;
        }

        final long sequence = ++requestSequence;
        renderer.showDetail(current, "Translating with desktop PLaMo…");
        provider.translate(current.text, current.languageHint, target,
                (translation, error) -> main.post(() -> {
                    if (sequence != requestSequence || !active ||
                            !preferences.enabled() ||
                            !preferences.desktopTranslationEnabled()) return;

                    String output = error == null
                            ? (translation == null ? "No translation returned." : translation)
                            : "Translation unavailable: " + error;
                    if (error == null && translation != null)
                        cache.write(current.text, target, translation);

                    List<TextCandidate> latest = freshView.capture();
                    refresh(latest);
                    TextCandidate stillVisible = match(latest, tapped.fingerprint);
                    if (stillVisible != null)
                        renderer.showDetail(stillVisible, output);
                }));
    }

    private static TextCandidate match(List<TextCandidate> candidates, String fingerprint) {
        for (TextCandidate c : candidates) {
            if (c.fingerprint.equals(fingerprint)) return c;
        }
        return null;
    }

    public void clear() {
        ++automaticGeneration;
        autoPendingFingerprint = null;
        autoPendingMissingSince = 0L;
        ++requestSequence;
        active = false;
        latestCandidates = Collections.emptyList();
        changeReactor.clear();
    }

    public void destroy() {
        clear();
        cache.clear();
        retryAfter.clear();
    }
}
