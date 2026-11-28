package com.wds.sixstars.contextoverlay;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.os.SystemClock;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;

import java.util.Collections;
import java.util.List;

/**
 * Opt-in, X-only, event-driven reader and overlay host.
 * It never records or forwards other app content and never uses screenshots.
 */
public final class XTranslationAccessibilityService extends AccessibilityService {
    private static final long SCAN_INTERVAL_MS = 120L;
    private static final long SCROLL_SCAN_INTERVAL_MS = 65L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final XVisibleTextReader reader = new XVisibleTextReader();
    private final XMeasuredScrollTracker scrollTracker = new XMeasuredScrollTracker();
    private OverlayPreferences preferences;
    private AccessibilityOverlayRenderer renderer;
    private OverlayTranslationCoordinator coordinator;
    private boolean destroyed;
    private TreeRuntime treeRuntime;
    private String readerStatus = "Service not scanned yet";
    private boolean scanScheduled;
    private boolean scrollSinceLastScan;
    private long lastTimelineScrollAt=-1L;
    // Event-driven normal scans. A low-frequency, foreground-only recovery
    // repairs a stale scene when X stops emitting scroll/content events.
    private static final long CAPTURE_RECOVERY_MS=4000L, SCENE_RECOVERY_MS=900L;
    private static final int MAX_UNKNOWN_ROOT_RETRIES=3;
    private boolean captureRecoveryScheduled, screenReceiverRegistered;
    private int unknownRootChecks;
    private long lastCaptureScanUptime=-1L,lastSceneScanUptime=-1L;
    private final Runnable captureRecovery=this::recoverCapture;
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())){
                stopCaptureRecovery();
                main.removeCallbacks(scan);main.removeCallbacks(settledScan);main.removeCallbacks(settledScanRetry);
                scanScheduled=false;scrollSinceLastScan=false;scrollTracker.reset();lastSceneScanUptime=-1L;
                if(coordinator!=null)coordinator.clear();
                if(treeRuntime!=null)treeRuntime.noteCaptureReaderState("Screen off; capture waiting");
            }else{
                unknownRootChecks=0;
                armCaptureRecovery();
            }
        }
    };
    // X may expose a recycled card before its text nodes are populated.
    // A bounded trailing scan verifies the settled layout, without polling.
    private static final long SETTLED_SCAN_MS=520;
    private final Runnable settledScan=()->{
        if(destroyed||!needsSettledScan())return;
        if(findXApplicationWindow()==null)return;
        if(!scanScheduled)refreshWindowSafely();
        else scheduleSettledRetry();
    };
    private final Runnable settledScanRetry=()->{
        if(!destroyed&&needsSettledScan()
                &&!scanScheduled&&findXApplicationWindow()!=null)refreshWindowSafely();
    };
    private void scheduleSettledRetry(){main.removeCallbacks(settledScanRetry);main.postDelayed(settledScanRetry,SCAN_INTERVAL_MS);}
    private void scheduleSettledScan(){
        main.removeCallbacks(settledScan);main.removeCallbacks(settledScanRetry);
        if(needsSettledScan())main.postDelayed(settledScan,SETTLED_SCAN_MS);
    }
    private final Runnable scan = () -> {
        scanScheduled = false;
        refreshWindowSafely();
    };

    private boolean captureEnabled(){
        return !destroyed&&preferences!=null&&preferences.enabled()&&treeRuntime!=null&&treeRuntime.enabled();
    }
    private boolean needsSettledScan(){
        return preferences!=null&&preferences.enabled()&&(preferences.autoPreview()||captureEnabled()||XAccessibilityInspector.armed());
    }
    private boolean screenAllowsCapture(){
        PowerManager power=getSystemService(PowerManager.class);
        KeyguardManager keyguard=getSystemService(KeyguardManager.class);
        return power!=null&&power.isInteractive()&&keyguard!=null&&!keyguard.isKeyguardLocked();
    }
    private boolean sceneRecoveryEnabled(){
        return !destroyed&&preferences!=null&&preferences.enabled()&&preferences.autoPreview();
    }
    private boolean recoveryEnabled(){return captureEnabled()||sceneRecoveryEnabled();}
    private long recoveryInterval(){return sceneRecoveryEnabled()?SCENE_RECOVERY_MS:CAPTURE_RECOVERY_MS;}
    private void armCaptureRecovery(){
        if(!captureRecoveryScheduled&&recoveryEnabled()&&screenAllowsCapture())
            captureRecoveryScheduled=main.postDelayed(captureRecovery,recoveryInterval());
    }
    private void stopCaptureRecovery(){
        main.removeCallbacks(captureRecovery);captureRecoveryScheduled=false;unknownRootChecks=0;
    }
    private void recoverCapture(){
        captureRecoveryScheduled=false;
        if(!recoveryEnabled()||!screenAllowsCapture()){unknownRootChecks=0;return;}
        boolean again=false;
        try{
            AccessibilityNodeInfo root=foregroundApplicationRoot();
            if(isXRoot(root)){
                unknownRootChecks=0;again=true;
                long now=SystemClock.uptimeMillis();
                if(sceneRecoveryEnabled()){
                    // Repair actual presentation, not merely the Tree.
                    // No scan when normal X events have kept it fresh.
                    if(!scanScheduled&&(lastSceneScanUptime<0||now-lastSceneScanUptime>=SCENE_RECOVERY_MS))
                        refreshWindowSafely();
                }else if(lastCaptureScanUptime<0||now-lastCaptureScanUptime>=CAPTURE_RECOVERY_MS)
                    captureFresh();
            }else if(root==null||root.getPackageName()==null){
                if(treeRuntime!=null)treeRuntime.noteCaptureReaderState("Foreground root unavailable; waiting for X");
                if(sceneRecoveryEnabled()&&renderer!=null){coordinator.noteUncertainScroll();renderer.uncertainScroll();}
                again=++unknownRootChecks<=MAX_UNKNOWN_ROOT_RETRIES;
            }else{
                hideOutsideX();
                unknownRootChecks=0;
            }
        }catch(RuntimeException error){
            noteCaptureFailure("Recovery",error);
            again=++unknownRootChecks<=MAX_UNKNOWN_ROOT_RETRIES;
        }finally{if(again)armCaptureRecovery();}
    }
    private void noteCaptureFailure(String stage,RuntimeException error){
        String reason=stage+" failed: "+error.getClass().getSimpleName();
        if(treeRuntime!=null)treeRuntime.noteCaptureReaderState(reason);
        android.util.Log.w("RootsCapture",reason);
    }
    private void refreshWindowSafely(){
        try{refreshWindow();}
        catch(RuntimeException error){
            noteCaptureFailure("Window scan",error);
            if(sceneRecoveryEnabled()&&renderer!=null){coordinator.noteUncertainScroll();renderer.uncertainScroll();}
            armCaptureRecovery();
        }
    }
    private void registerScreenEvents(){
        if(screenReceiverRegistered)return;
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
        try{
            if(Build.VERSION.SDK_INT>=33)registerReceiver(screenReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenReceiver,filter);
            screenReceiverRegistered=true;
        }catch(RuntimeException error){noteCaptureFailure("Screen lifecycle registration",error);}
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        destroyed = false;
        scrollTracker.reset();
        preferences = new OverlayPreferences(this);
        renderer = new AccessibilityOverlayRenderer(this);
        coordinator = new OverlayTranslationCoordinator(
                TranslationRuntime.provider(this), preferences, renderer, this::captureFresh);
        OverlayDiagnostics.record(this, preferences.enabled()
                ? "Accessibility service connected; ready for X"
                : "Accessibility service connected; enable consent inside Roots",
                0, 0, 0, 0, "");
        treeRuntime=NativeTree.available()?TreeRuntime.get(this):null;
        coordinator.setTree(treeRuntime);
        if(treeRuntime!=null)treeRuntime.noteCaptureReaderState("Accessibility service connected");
        lastCaptureScanUptime=-1L;lastSceneScanUptime=-1L;stopCaptureRecovery();registerScreenEvents();
        armCaptureRecovery();
        TranslationRuntime.startCompute(this);
        scheduleScan();scheduleSettledScan();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || destroyed || preferences == null) return;
        if (!preferences.enabled()) {
            stopCaptureRecovery();
            XAccessibilityInspector.cancel();
            if(treeRuntime!=null)treeRuntime.noteCaptureReaderState("Accessibility consent OFF");
            main.removeCallbacks(scan);main.removeCallbacks(settledScan);main.removeCallbacks(settledScanRetry);
            scanScheduled = false;
            coordinator.clear();
            TranslationRuntime.stopCompute(this);
            TranslationRuntime.disconnect();
            OverlayDiagnostics.record(this,
                    "Accessibility enabled, but in-app consent is OFF",
                    0, 0, 0, 0, "");
            return;
        }

        CharSequence pkg = event.getPackageName();
        int kind = event.getEventType();
        // Window-list changes often have no package name. They can be the only
        // signal after an app switch, unlock or a temporarily missing X root.
        if(kind==AccessibilityEvent.TYPE_WINDOWS_CHANGED){
            if(recoveryEnabled()){unknownRootChecks=0;armCaptureRecovery();}
            return;
        }
        if(kind==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED||
                (pkg!=null&&XVisibleTextReader.X_PACKAGE.contentEquals(pkg))){
            unknownRootChecks=0;armCaptureRecovery();
        }
        TranslationRuntime.startCompute(this);
        if (pkg != null && getPackageName().contentEquals(pkg)) {
            // Our own TYPE_ACCESSIBILITY_OVERLAY windows cause window events.
            // Never destroy/recreate translated text merely because an overlay
            // was added, toggled or expanded. Only a *real* foreground
            // application switch to Roots should clear it.
            if (kind == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && isNonXApplicationForeground()) hideOutsideX();
            return;
        }
        if (pkg == null || !XVisibleTextReader.X_PACKAGE.contentEquals(pkg)) {
            if (kind == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // The reply composer can show an INPUT_METHOD window whose
                // events come from the keyboard, not X. Ignore those events
                // when X is still the topmost TYPE_APPLICATION window.
                if (isNonXApplicationForeground()) hideOutsideX();
                else if (findXApplicationWindow() != null) {scheduleScan();scheduleSettledScan();}
            }
            return;
        }

        // Sample only ordinary foreground X events; never inspect keyboard
        // or editable/password sources, or retain the event itself.
        if(XAccessibilityInspector.eventDue(captureEnabled(),kind)&&screenAllowsCapture()&&!event.isPassword()){
            try{
                AccessibilityNodeInfo foreground=findXApplicationWindow();
                if(isXRoot(foreground)){
                    AccessibilityNodeInfo source=event.getSource();
                    if(source==null||(!source.isEditable()&&!source.isPassword()))
                        XAccessibilityInspector.noteEvent(event);
                }
            }catch(RuntimeException ignored){}
        }
        if (kind == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastTimelineScrollAt=SystemClock.uptimeMillis();
            if(!scrollSinceLastScan && scanScheduled){
                main.removeCallbacks(scan);scanScheduled=false;
            }
            scrollSinceLastScan = true;
            if (preferences.autoPreview() && renderer != null) {
                // Direct pixel delta on API 28+, or actual scrollY offset
                // difference from the SAME window/class as a fallback.
                // Neither source is extrapolated from time or gesture speed.
                int directY = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? event.getScrollDeltaY() : 0;
                String origin = event.getWindowId() + ":" +
                        String.valueOf(event.getClassName());
                XMeasuredScrollTracker.Result movement = scrollTracker.observe(
                        origin, directY, event.getScrollY());
                if (movement.measured && movement.deltaY != 0) {
                    coordinator.noteMeasuredScroll(movement.deltaY,android.os.SystemClock.uptimeMillis());
                    if (movement.deltaY != 0)
                        renderer.measuredScroll(0, movement.deltaY);
                } else {
                    // X supplied no reliable vertical pixel displacement.
                    // Hide rather than let old labels hover until rescanning.
                    coordinator.noteUncertainScroll();
                    renderer.uncertainScroll();
                }
            }
        } else if (kind == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                preferences.autoPreview() && renderer != null) {
            // A new X page invalidates the last scroll source and geometry.
            scrollTracker.reset();
            coordinator.noteSceneChanged();
            scrollSinceLastScan = true;
            renderer.uncertainScroll();
            renderer.resetAutoWindowAttachment();
        }
        scheduleScan();
        scheduleSettledScan();
    }

    private void hideOutsideX() {
        stopCaptureRecovery();
        if(treeRuntime!=null)treeRuntime.noteCaptureReaderState("Outside X; capture waiting");
        main.removeCallbacks(scan);main.removeCallbacks(settledScan);main.removeCallbacks(settledScanRetry);
        scanScheduled = false;
        scrollSinceLastScan = false;
        lastSceneScanUptime=-1L;
        scrollTracker.reset();
        coordinator.clear();
        // Keep the paired compute socket alive across foreground apps.
        OverlayDiagnostics.record(this, "Outside X; overlays hidden",
                0, 0, 0, 0, "");
    }

    private void publishAutoWindowTarget() {
        if (renderer == null || preferences == null || !preferences.autoPreview()) return;
        AccessibilityWindowInfo xWindow = foregroundApplicationWindow();
        if (xWindow == null || !isXRoot(xWindow.getRoot())) {
            renderer.setAutoTargetWindow(-1, null);
            return;
        }
        Rect bounds = new Rect();
        xWindow.getBoundsInScreen(bounds);
        renderer.setAutoTargetWindow(xWindow.getId(), bounds);
    }

    /** Only application windows count; keyboards and our overlays do not. */
    private boolean isNonXApplicationForeground() {
        AccessibilityNodeInfo root = foregroundApplicationRoot();
        return root != null && root.getPackageName() != null
                && !XVisibleTextReader.X_PACKAGE.contentEquals(root.getPackageName());
    }

    private void scheduleScan() {
        // Do not restart the debounce delay for every scroll event. The X feed
        // may emit continuous TYPE_VIEW_SCROLLED events more often than 120 ms.
        // Coalesce those events into one periodic frame while the user scrolls.
        if (scanScheduled) return;
        scanScheduled = true;
        main.postDelayed(scan, scrollSinceLastScan ? SCROLL_SCAN_INTERVAL_MS : SCAN_INTERVAL_MS);
    }

    private void refreshWindow() {
        if (destroyed || coordinator == null) return;
        if (preferences == null || !preferences.enabled()) {
            coordinator.clear();
            OverlayDiagnostics.record(this, "In-app consent OFF",
                    0, 0, 0, 0, "");
            return;
        }
        // Verify the actual foreground X application window BEFORE any
        // translation is placed. Other apps, keyboards and our own overlay
        // windows are never eligible SurfaceControl parents.
        // Retain the verified snapshot before touching the renderer. A compositor
        // failure must not prevent Tree intake.
        List<TextCandidate> candidates = captureFresh();
        if(!readerStatus.startsWith("X active;")){
            // A failed/absent root cannot authorize replay of old geometry.
            if(preferences.autoPreview()){coordinator.noteUncertainScroll();renderer.uncertainScroll();}
            if(isNonXApplicationForeground()){hideOutsideX();return;}
        }
        publishAutoWindowTarget();
        boolean scrolling = scrollSinceLastScan;
        scrollSinceLastScan = false;
        // Never treat a single empty accessibility snapshot as app exit.
        // A reply composer may briefly make X's virtual text tree empty.
        coordinator.setViewportHeight(getResources().getDisplayMetrics().heightPixels);
        coordinator.refresh(candidates,
                readerStatus.startsWith("X active;") ? reader.lastDiscovered() : Collections.emptyList(),
                scrolling);
        if(readerStatus.startsWith("X active;"))lastSceneScanUptime=SystemClock.uptimeMillis();
        boolean scannedX = readerStatus.startsWith("X");
        OverlayDiagnostics.record(this, readerStatus,
                scannedX ? reader.lastVisitedNodes() : 0,
                scannedX ? reader.lastTextNodes() : 0,
                candidates.size(),
                renderer.visibleBadgeCount(),
                renderer.lastWindowError());

        if (readerStatus.startsWith("X active;")) {
            int eligible = 0;
            int placeable = 0;
            if (preferences.autoPreview()) {
                android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                for (TextCandidate c : candidates) {
                    if (!AutoTranslationEligibilityService.accepts(
                            c, preferences.targetLanguage())) continue;
                    eligible++;
                    if (AutoInlinePlacementService.locate(
                            c.bounds.left, c.bounds.top, c.bounds.right, c.bounds.bottom,
                            metrics.widthPixels, metrics.heightPixels,
                            metrics.density) != null) placeable++;
                }
            }
            OverlayDiagnostics.recordAuto(this, preferences.autoPreview(),
                    eligible, placeable, renderer.visibleTranslationCount());
            OverlayDiagnostics.recordAuthorCounts(this, reader.lastAvatarCount(),
                    reader.lastAuthorSampleCount(), reader.lastAuthorMatchCount(),
                    reader.lastClippedNameCount());
        }
    }

    private List<TextCandidate> captureFresh() {
        try{return readVisibleX();}
        catch(RuntimeException error){
            readerStatus="Capture scan failed: "+error.getClass().getSimpleName();
            noteCaptureFailure("Visible scan",error);
            return Collections.emptyList();
        }
    }
    private List<TextCandidate> readVisibleX() {
        if (destroyed || preferences == null || !preferences.enabled()) {
            readerStatus = "In-app consent OFF";
            return Collections.emptyList();
        }
        // Retention requires a positively verified foreground application.
        // Translation can still use its existing active-root fallback.
        AccessibilityNodeInfo foreground=findXApplicationWindow();
        boolean captureRequested=captureEnabled();
        boolean screenReady=captureRequested&&screenAllowsCapture();
        boolean retain=screenReady&&isXRoot(foreground);
        if(captureRequested&&!retain)treeRuntime.noteCaptureReaderState(screenReady?
            "Waiting for verified foreground X":"Screen locked or off; capture waiting");
        long epoch=retain?treeRuntime.captureEpoch():-1L;
        XAccessibilityInspector.Scan inspection=isXRoot(foreground)&&screenAllowsCapture()?XAccessibilityInspector.begin(retain,lastTimelineScrollAt<0||SystemClock.uptimeMillis()-lastTimelineScrollAt>=SETTLED_SCAN_MS):null;
        AccessibilityNodeInfo root = retain||inspection!=null?foreground:getRootInActiveWindow();
        if (!isXRoot(root)) {
            // Android may report the keyboard or our tiny language/More
            // control as the active window while X remains the foreground
            // application. Read X's highest application window instead;
            // never read a background X window behind another app.
            root = findXApplicationWindow();
        }
        if (!isXRoot(root)) {
            readerStatus = "No visible X application window root";
            return Collections.emptyList();
        }

        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        // All coordinates and overlay windows use screen pixels, not dp.
        List<TextCandidate> found = reader.find(root, metrics.widthPixels, metrics.heightPixels, metrics.scaledDensity,retain,inspection);
        if(inspection!=null)XAccessibilityInspector.finish(inspection,preferences.enabled()&&screenAllowsCapture()&&isXRoot(findXApplicationWindow())&&(!inspection.automatic||captureEnabled()&&treeRuntime.captureEpoch()==epoch),reader.lastKnowledgeBounded());
        if(retain){
            // Recheck foreground and consent after traversal; no extra tree walk.
            boolean stillX=screenAllowsCapture()&&isXRoot(findXApplicationWindow());
            treeRuntime.acceptVisibleSnapshot(reader.lastKnowledge(),epoch,stillX,reader.lastKnowledgeBounded());
            if(stillX&&treeRuntime.enabled()&&treeRuntime.captureEpoch()==epoch){
                lastCaptureScanUptime=SystemClock.uptimeMillis();
                for(TextCandidate candidate:found)candidate.knowledgeEpoch=epoch;
                for(TextCandidate candidate:reader.lastDiscovered())candidate.knowledgeEpoch=epoch;
            }
        }
        readerStatus = found.isEmpty()
                ? (reader.lastPostDetail() ? "X active; post detail heading found, no body text" : "X active; no eligible visible text found")
                : (reader.lastPostDetail() ? "X active; post detail text found" : "X active; candidate text found");
        return found;
    }

    private static boolean isXRoot(AccessibilityNodeInfo node) {
        return node != null && node.getPackageName() != null
                && XVisibleTextReader.X_PACKAGE.contentEquals(node.getPackageName());
    }

    private AccessibilityNodeInfo findXApplicationWindow() {
        AccessibilityNodeInfo root = foregroundApplicationRoot();
        return isXRoot(root) ? root : null;
    }

    private AccessibilityNodeInfo foregroundApplicationRoot() {
        AccessibilityWindowInfo window = foregroundApplicationWindow();
        return window == null ? null : window.getRoot();
    }

    private AccessibilityWindowInfo foregroundApplicationWindow() {
        // Ignore keyboard TYPE_INPUT_METHOD and Roots accessibility
        // overlays when deciding whether X is still the active application.
        AccessibilityWindowInfo foreground = null;
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window == null ||
                    window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION)
                continue;
            if (foreground == null || window.getLayer() > foreground.getLayer())
                foreground = window;
        }
        return foreground;
    }

    @Override
    public void onInterrupt() {
        stopCaptureRecovery();
        if(treeRuntime!=null)treeRuntime.noteCaptureReaderState("Accessibility interrupted; awaiting recovery");
        main.removeCallbacks(scan);main.removeCallbacks(settledScan);main.removeCallbacks(settledScanRetry);
        scanScheduled = false;
        if (coordinator != null) coordinator.clear();
        scrollTracker.reset();
        TranslationRuntime.stopCompute(this);
        TranslationRuntime.disconnect();
        armCaptureRecovery();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        stopCaptureRecovery();
        if(screenReceiverRegistered){unregisterReceiver(screenReceiver);screenReceiverRegistered=false;}
        if(treeRuntime!=null)treeRuntime.noteCaptureReaderState("Accessibility service stopped");
        treeRuntime=null;
        main.removeCallbacks(scan);main.removeCallbacks(settledScan);main.removeCallbacks(settledScanRetry);
        scanScheduled = false;
        if (coordinator != null) coordinator.destroy();
        if (renderer != null) renderer.destroy();
        TranslationRuntime.stopCompute(this);
        TranslationRuntime.disconnect();
        OverlayDiagnostics.record(this, "Accessibility service stopped",
                0, 0, 0, 0, "");
        super.onDestroy();
    }
}
