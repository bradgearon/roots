package com.wds.sixstars.contextoverlay;

import android.accessibilityservice.AccessibilityService;
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
    // X may expose a recycled card before its text nodes are populated.
    // A bounded trailing scan verifies the settled layout, without polling.
    private static final long SETTLED_SCAN_MS=520;
    private final Runnable settledScan=()->{
        if(destroyed||preferences==null||!preferences.enabled()||!preferences.autoPreview())return;
        if(findXApplicationWindow()==null)return;
        if(!scanScheduled)refreshWindow();
        else scheduleSettledRetry();
    };
    private final Runnable settledScanRetry=()->{
        if(!destroyed&&preferences!=null&&preferences.enabled()&&preferences.autoPreview()
                &&!scanScheduled&&findXApplicationWindow()!=null)refreshWindow();
    };
    private void scheduleSettledRetry(){main.removeCallbacks(settledScanRetry);main.postDelayed(settledScanRetry,SCAN_INTERVAL_MS);}
    private void scheduleSettledScan(){
        main.removeCallbacks(settledScan);main.removeCallbacks(settledScanRetry);
        if(preferences!=null&&preferences.enabled()&&preferences.autoPreview())
            main.postDelayed(settledScan,SETTLED_SCAN_MS);
    }
    private final Runnable scan = () -> {
        scanScheduled = false;
        refreshWindow();
    };

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
        TranslationRuntime.startCompute(this);
        scheduleScan();scheduleSettledScan();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || destroyed || preferences == null) return;
        if (!preferences.enabled()) {
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

        TranslationRuntime.startCompute(this);
        CharSequence pkg = event.getPackageName();
        int kind = event.getEventType();
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

        if (kind == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
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
        main.removeCallbacks(scan);main.removeCallbacks(settledScan);main.removeCallbacks(settledScanRetry);
        scanScheduled = false;
        scrollSinceLastScan = false;
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
        publishAutoWindowTarget();
        List<TextCandidate> candidates = captureFresh();
        boolean scrolling = scrollSinceLastScan;
        scrollSinceLastScan = false;
        // Never treat a single empty accessibility snapshot as app exit.
        // A reply composer may briefly make X's virtual text tree empty.
        coordinator.setViewportHeight(getResources().getDisplayMetrics().heightPixels);
        coordinator.refresh(candidates,
                readerStatus.startsWith("X active;") ? reader.lastDiscovered() : Collections.emptyList(),
                scrolling);
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
        if (destroyed || preferences == null || !preferences.enabled()) {
            readerStatus = "In-app consent OFF";
            return Collections.emptyList();
        }
        // Retention requires a positively verified foreground application.
        // Translation can still use its existing active-root fallback.
        AccessibilityNodeInfo foreground=findXApplicationWindow();
        boolean retain=treeRuntime!=null && treeRuntime.enabled() && isXRoot(foreground);
        long epoch=retain?treeRuntime.captureEpoch():-1L;
        AccessibilityNodeInfo root = retain?foreground:getRootInActiveWindow();
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
        List<TextCandidate> found = reader.find(root, metrics.widthPixels, metrics.heightPixels, metrics.scaledDensity,retain);
        if(retain){
            // Recheck foreground and consent after traversal; no extra tree walk.
            boolean stillX=isXRoot(findXApplicationWindow());
            treeRuntime.acceptVisibleSnapshot(reader.lastKnowledge(),epoch,stillX,reader.lastKnowledgeBounded());
            if(stillX&&treeRuntime.enabled()&&treeRuntime.captureEpoch()==epoch){
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
        main.removeCallbacks(scan);main.removeCallbacks(settledScan);main.removeCallbacks(settledScanRetry);
        scanScheduled = false;
        if (coordinator != null) coordinator.clear();
        scrollTracker.reset();
        TranslationRuntime.stopCompute(this);
        TranslationRuntime.disconnect();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
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
