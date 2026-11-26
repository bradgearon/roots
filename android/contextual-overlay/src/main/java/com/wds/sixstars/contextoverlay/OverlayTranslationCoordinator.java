package com.wds.sixstars.contextoverlay;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import android.os.SystemClock;

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
    private final Map<String, Pending> pool = new LinkedHashMap<>();
    private final Set<String> visible = new HashSet<>();
    private final Set<String> executing = new HashSet<>();
    private String poolTarget;
    private static final int MAX_PENDING = 256;
    private static final long RETAIN_MS = 120_000;
    private static final class Pending {
        TextCandidate candidate;
        final long firstSeen;
        long lastSeen;
        Pending(TextCandidate candidate,long now){
            this.candidate=candidate;firstSeen=lastSeen=now;
        }
    }
    private final Runnable retryWake = this::pump;
    private final Runnable motionWake = this::pump;
    private final TranslationDemand demand = new TranslationDemand();
    private final TranslationLatency latency = new TranslationLatency();
    private int viewportHeight = 1;

    // A single outstanding request keeps waiting work reprioritizable on the
    // main thread. Both built-in providers have one serial inference worker.
    private static final int PROVIDER_SLOTS = 1;

    public void setViewportHeight(int height) { viewportHeight=Math.max(1,height); }
    public void noteMeasuredScroll(int dy,long now) {
        demand.measuredScroll(dy,now);
        main.removeCallbacks(motionWake);
        if(active&&preferences.autoPreview())main.postDelayed(motionWake,TranslationDemand.MOTION_IDLE_MS+1);
    }
    public void noteUncertainScroll() {
        demand.invalidate();
        main.removeCallbacks(motionWake);
        publishActivity();
    }
    public void noteSceneChanged() {
        noteUncertainScroll();
        latestCandidates=Collections.emptyList();
        pool.clear();visible.clear();manualCandidate=null;manualTarget=null;
        publishActivity();
    }
    private String profile() {
        return preferences.desktopTranslationEnabled()?"desktop":
                (preferences.localVulkanEnabled()?"vulkan:":"cpu:")+preferences.localThreads();
    }

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
        refresh(current,current,scrolling);
    }

    public void refresh(List<TextCandidate> current,List<TextCandidate> discovered,boolean scrolling) {
        refreshSnapshot(current,discovered,scrolling);
    }

    private void refreshSnapshot(List<TextCandidate> current,List<TextCandidate> discovered,boolean scrolling) {
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
        if(!target.equals(poolTarget)){
            pool.clear();retryAfter.clear();poolTarget=target;
        }
        demand.snapshot(viewportHeight);
        {
            visible.clear();
            for(TextCandidate candidate:current)visible.add(candidate.fingerprint);
            // Reader currently exposes only authorized visible nodes.
            // Layout rejection must not lower their translation priority.
            for(TextCandidate candidate:discovered)visible.add(candidate.fingerprint);
            if(automatic){
                ArrayList<TextCandidate> combined=new ArrayList<>(discovered);
                combined.addAll(current);
                remember(combined,target);
            }
        }
        if(!automatic)pool.clear();
        publishCached();
        publishActivity();
        pump();
    }

    // Completion supplies text, never geometry. In particular it must NOT
    // re-publish the previous accessibility snapshot after a measured scroll.
    private void publishCached() {
        String target=preferences.targetLanguage();
        boolean automatic=preferences.autoPreview();
        if(automatic&&!demand.trusted())return;
        for(TextCandidate candidate:latestCandidates){
            String existing=cache.read(candidate.text,target);
            if(existing==null)continue;
            if(automatic){
                if(shouldTranslateAutomatically(candidate,target))
                    changeReactor.translated(candidate,existing);
            }else changeReactor.prepared(candidate.fingerprint);
        }
    }

    private void publishActivity(){
        Set<String> ids=new HashSet<>();
        if(active && demand.trusted() && preferences.autoPreview() && preferences.showTranslationActivity()){
            String target=preferences.targetLanguage();
            for(TextCandidate candidate:latestCandidates)
                if(executing.contains(key(candidate,target)))ids.add(candidate.fingerprint);
        }
        renderer.setActiveTranslations(ids);
    }

    private static boolean shouldTranslateAutomatically(TextCandidate c, String target) {
        return AutoTranslationEligibilityService.accepts(c, target);
    }

    private static String key(TextCandidate candidate,String target) {
        return TextFingerprint.of(candidate.text) + ":" + target;
    }

    private void remember(List<TextCandidate> discovered,String target){
        long now=SystemClock.uptimeMillis();
        pool.values().removeIf(p->now-p.lastSeen>RETAIN_MS
                || cache.read(p.candidate.text,target)!=null);
        for(TextCandidate candidate:discovered){
            if(!shouldTranslateAutomatically(candidate,target)
                    ||cache.read(candidate.text,target)!=null)continue;
            String k=candidate.fingerprint+":"+target;
            Pending p=pool.get(k);
            if(p==null)pool.put(k,new Pending(candidate,now));
            else {p.candidate=candidate;p.lastSeen=now;}
        }
        final String backend=profile();
        final double velocity=demand.velocity(now);
        while(pool.size()>MAX_PENDING){
            String lowest=null;long lowestScore=Long.MAX_VALUE;
            for(Map.Entry<String,Pending> item:pool.entrySet()){
                long score=priority(item.getValue(),now,backend,velocity);
                if(score<lowestScore){lowestScore=score;lowest=item.getKey();}
            }
            pool.remove(lowest);
        }
    }

    private boolean currentlyVisible(TextCandidate c) {
        return c!=null && demand.trusted() && visible.contains(c.fingerprint)
                && c.bounds.bottom-demand.offset()>0 && c.bounds.top-demand.offset()<demand.height();
    }

    private long priority(Pending p,long now,String backend,double velocity){
        TextCandidate c=p.candidate;
        return TranslationWorkPriority.score(visible.contains(c.fingerprint),c.authorName,
                c.bounds.top,c.bounds.bottom,demand.height(),demand.offset(),velocity,
                latency.predict(backend,c.text.length()),now-p.firstSeen,now-p.lastSeen);
    }

    /** Main-thread admission. Select work only when a provider slot is free. */
    private void pump() {
        if (destroyed || !active || !preferences.enabled()) return;
        int limit = PROVIDER_SLOTS;
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
        if (!preferences.autoPreview() || !preferences.translationEnabled() || !demand.trusted() || !target.equals(poolTarget) || visible.isEmpty()) return;
        main.removeCallbacks(retryWake);
        if(!admission.hasRoom(limit))return;
        long now=SystemClock.uptimeMillis();
        final String backend=profile();
        final double velocity=demand.velocity(now);
        pool.values().removeIf(p->now-p.lastSeen>RETAIN_MS
                ||cache.read(p.candidate.text,target)!=null);
        ArrayList<Pending> ranked=new ArrayList<>(pool.values());
        ranked.sort(Comparator.comparingLong((Pending p)->priority(p,now,backend,velocity)).reversed()
                .thenComparingInt(p->p.candidate.bounds.top)
                .thenComparingInt(p->p.candidate.order));
        long nextRetry=Long.MAX_VALUE;
        for(Pending p:ranked){
            if(!admission.hasRoom(limit))break;
            if(priority(p,now,backend,velocity)<=0)break;
            TextCandidate candidate=p.candidate;
            if(!shouldTranslateAutomatically(candidate,target))continue;
            String k=key(candidate,target);
            if(admission.contains(k))continue;
            Long retry=retryAfter.get(k);
            if(retry!=null&&System.currentTimeMillis()<retry){
                nextRetry=Math.min(nextRetry,retry);
                continue;
            }
            submit(candidate,target);
        }
        if(admission.hasRoom(limit)&&nextRetry!=Long.MAX_VALUE)
            main.postDelayed(retryWake,Math.max(1,nextRetry-System.currentTimeMillis()));
        long idleDelay=demand.idleDelay(now);
        if(admission.hasRoom(limit)&&idleDelay>0&&!ranked.isEmpty()){
            main.removeCallbacks(motionWake);
            main.postDelayed(motionWake,idleDelay+1);
        }
    }

    private void submit(TextCandidate candidate,String target) {
        String k=key(candidate,target);
        if(!admission.tryAcquire(k,PROVIDER_SLOTS))return;
        long epoch=consentEpoch;
        final String submittedProfile=profile();
        final long submittedAt=SystemClock.elapsedRealtime();
        final long predicted=latency.predict(submittedProfile,candidate.text.length());
        final boolean wasVisible=currentlyVisible(candidate);
        android.util.Log.d("RootsTranslationSchedule","submit profile="+submittedProfile+
                " kind="+(candidate.authorName?"name":"body")+" lane="+(wasVisible?"foreground":"rollup")+" visible="+wasVisible+
                " direction="+(int)Math.signum(demand.velocity(SystemClock.uptimeMillis()))+
                " predicted_ms="+predicted+" pending="+pool.size());
        java.util.concurrent.atomic.AtomicBoolean delivered=new java.util.concurrent.atomic.AtomicBoolean();
        TranslationProvider.Result callback=new TranslationProvider.Result(){
            @Override public void started(){
                main.post(()->{
                    if(destroyed || delivered.get() || epoch!=consentEpoch)return;
                    executing.add(k);
                    publishActivity();
                });
            }
            @Override public void partial(String prefix){
                if(prefix==null||prefix.trim().isEmpty()||delivered.get())return;
                main.post(()->{
                    if(destroyed||!active||delivered.get()||epoch!=consentEpoch||!preferences.enabled()||!target.equals(preferences.targetLanguage())||!submittedProfile.equals(profile()))return;
                    TextCandidate current=match(latestCandidates,candidate.fingerprint);
                    if(current==null)return;
                    if(preferences.autoPreview()&&demand.trusted()&&shouldTranslateAutomatically(current,target))renderer.showAutoPartial(current,prefix);
                    else if(manualCandidate!=null&&k.equals(key(manualCandidate,manualTarget)))renderer.updateDetailPartial(current,prefix);
                });
            }
            @Override public void completed(String translated,String error){
            if(!delivered.compareAndSet(false,true))return;
            final long elapsed=SystemClock.elapsedRealtime()-submittedAt;
            main.post(()->{
            executing.remove(k);
            renderer.clearAutoPartial(candidate.fingerprint);
            publishActivity();
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
                if(submittedProfile.equals(profile()))latency.observe(submittedProfile,candidate.text.length(),elapsed);
                Pending latest=pool.get(candidate.fingerprint+":"+target);
                android.util.Log.d("RootsTranslationSchedule","complete profile="+submittedProfile+
                        " elapsed_ms="+elapsed+" predicted_ms="+predicted+
                        " still_visible="+currentlyVisible(latest==null?match(latestCandidates,candidate.fingerprint):latest.candidate));
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
            publishCached();
            publishActivity();
            pump();
        });}};
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
        main.removeCallbacks(retryWake);
        main.removeCallbacks(motionWake);
        demand.invalidate();
        pool.clear(); visible.clear(); poolTarget=null;
        if(!preferences.enabled() || destroyed)executing.clear();
        changeReactor.clear();
    }

    public void destroy() {
        destroyed=true;
        clear();
        cache.clear();
        retryAfter.clear();
    }
}
