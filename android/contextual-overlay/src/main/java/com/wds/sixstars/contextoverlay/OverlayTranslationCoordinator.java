package com.wds.sixstars.contextoverlay;

import android.os.Handler;
import android.os.Looper;

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

    // The provider owns submitted work until its callback, not until a post
    // leaves the viewport. Manual and automatic requests share admission.
    private final TranslationAdmission admission = new TranslationAdmission();
    private long consentEpoch;
    private boolean active, destroyed;
    private TextCandidate manualCandidate;
    private String manualTarget;

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
        if(destroyed)return;
        latestCandidates = Collections.unmodifiableList(new ArrayList<>(current));
        active = preferences.enabled();
        if (!active) {
            clear();
            return;
        }

        boolean automatic = preferences.autoPreview();
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

        pump();
    }

    private static boolean shouldTranslateAutomatically(TextCandidate c, String target) {
        return AutoTranslationEligibilityService.accepts(c, target);
    }

    private static String key(TextCandidate candidate,String target) {
        return candidate.fingerprint + ":" + target;
    }

    /** Main-thread admission. Never queue obsolete automatic work locally. */
    private void pump() {
        if (destroyed || !active || !preferences.enabled()) return;
        int limit = preferences.translationRequestLimit();
        String target = preferences.targetLanguage();
        if (manualCandidate != null) {
            TextCandidate current = match(latestCandidates, manualCandidate.fingerprint);
            if (current == null || !target.equals(manualTarget)) {
                manualCandidate = null; manualTarget = null;
            } else {
                String cached = cache.read(current.text, target);
                if (cached != null) {
                    manualCandidate = null; manualTarget = null;
                    renderer.showDetail(current, cached);
                } else if (!admission.contains(key(current,target))) {
                    if (!admission.hasRoom(limit)) return;
                    submit(current,target);
                }
                // Do not let speculative jobs jump ahead of an explicit tap.
                if (manualCandidate != null) return;
            }
        }
        if (!preferences.autoPreview() || !preferences.translationEnabled()) return;
        for (TextCandidate candidate : latestCandidates) {
            if (!admission.hasRoom(limit)) break;
            if (!shouldTranslateAutomatically(candidate,target) ||
                    cache.read(candidate.text,target)!=null) continue;
            String k=key(candidate,target);
            Long retry=retryAfter.get(k);
            if (admission.contains(k) || (retry!=null && System.currentTimeMillis()<retry)) continue;
            submit(candidate,target);
        }
    }

    private void submit(TextCandidate candidate,String target) {
        String k=key(candidate,target);
        if(!admission.tryAcquire(k,preferences.translationRequestLimit()))return;
        long epoch=consentEpoch;
        TranslationProvider.Result callback=(translated,error)->main.post(()->{
            // Always release the actual outstanding request, including on
            // scroll, mode changes and X exit. Duplicate callbacks are ignored.
            if(!admission.release(k))return;
            if(destroyed)return;
            if(!preferences.enabled()||epoch!=consentEpoch){
                if(active&&preferences.enabled())pump();
                return;
            }
            boolean ok=error==null&&translated!=null&&!translated.trim().isEmpty();
            if(ok){
                cache.write(candidate.text,target,translated);
                retryAfter.remove(k);
            }else{
                if(retryAfter.size()>100)retryAfter.clear();
                retryAfter.put(k,System.currentTimeMillis()+AUTO_RETRY_COOLDOWN_MS);
            }
            // Results remain useful after scrolling away; render only against
            // the latest scene, never the original request's geometry.
            if(!active)return;
            TextCandidate current=match(latestCandidates,candidate.fingerprint);
            if(manualCandidate!=null&&k.equals(key(manualCandidate,manualTarget))){
                manualCandidate=null;manualTarget=null;
                if(current!=null&&target.equals(preferences.targetLanguage()))
                    renderer.showDetail(current,ok?translated:"Translation unavailable: "+(error==null?"No translation returned.":error));
            }
            refresh(latestCandidates);
        });
        try {
            provider.translate(candidate.text,candidate.languageHint,target,callback);
        }catch(RuntimeException ex){
            callback.completed(null,"Translation provider unavailable.");
        }
    }

    private void translateAtTap(TextCandidate tapped) {
        if(destroyed||!active||!preferences.enabled())return;
        List<TextCandidate> fresh=freshView.capture();
        TextCandidate current=match(fresh,tapped.fingerprint);
        if(current==null)return;
        latestCandidates=Collections.unmodifiableList(new ArrayList<>(fresh));
        String target=preferences.targetLanguage();
        String cached=cache.read(current.text,target);
        manualCandidate=null;manualTarget=null;
        if(cached!=null){renderer.showDetail(current,cached);return;}
        manualCandidate=current;manualTarget=target;
        renderer.showDetail(current,"Translating with PLaMo…");
        pump();
    }

    private static TextCandidate match(List<TextCandidate> candidates, String fingerprint) {
        for (TextCandidate c : candidates) {
            if (c.fingerprint.equals(fingerprint)) return c;
        }
        return null;
    }

    public void clear() {
        // Leaving X drops presentation and pending manual demand, but does
        // not pretend an already-submitted native job has finished.
        if(!preferences.enabled()){
            ++consentEpoch; cache.clear(); retryAfter.clear();
        }
        manualCandidate=null;manualTarget=null;
        active = false;
        latestCandidates = Collections.emptyList();
        changeReactor.clear();
    }

    public void destroy() {
        destroyed=true;
        clear();
        cache.clear();
        retryAfter.clear();
    }
}
