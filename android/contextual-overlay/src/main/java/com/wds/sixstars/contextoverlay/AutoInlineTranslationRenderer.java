package com.wds.sixstars.contextoverlay;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;

import java.util.List;

/**
 * Compatibility facade for the restored Auto inline renderer API.
 *
 * The old implementation directly created/removed three WindowManager views
 * for each post inside accessibility callbacks. The actual rendering now
 * lives entirely in AutoOverlaySceneController, which owns one shared text
 * canvas, measured positions, touch controls, and a Choreographer scheduler.
 *
 * Manual mode never calls this facade.
 */
public final class AutoInlineTranslationRenderer {
    private final AutoOverlaySceneController scene;

    public AutoInlineTranslationRenderer(AccessibilityService service) {
        scene = new AutoOverlaySceneController(service);
    }

    public int visibleCount() { return scene.visibleCount(); }

    public void resetWindowAttachment() { scene.resetWindowAttachment(); }

    public String lastWindowError() { return scene.lastError(); }

    /** Independently verified topmost X window for API 34+ surface attachment. */
    public void setTargetWindow(int id, Rect bounds) {
        scene.setTargetWindow(id, bounds,null);
    }
    void setTargetWindow(int id,Rect bounds,XOverlayOrigin.Offset origin){
        scene.setTargetWindow(id,bounds,origin);
    }

    /** Accessibility snapshot: positions only, no drawing or model requests. */
    public void reconcile(List<TextCandidate> candidates, boolean scrolling) {
        scene.publishPositions(candidates, scrolling);
    }

    /** Model callback: text only, no accessibility-tree traversal. */
    public void partial(TextCandidate candidate,String prefix){scene.publishPartial(candidate.fingerprint,prefix);}
    public void clearPartial(String id){scene.clearPartial(id);}
    public void show(TextCandidate candidate, String translation) {
        if (candidate != null) scene.publishTranslation(candidate.fingerprint, translation);
    }

    /** Exact reported X scroll delta, applied in pixels to the existing scene. */
    public void measuredScroll(int deltaX, int deltaY) {
        scene.measuredScroll(deltaX, deltaY);
    }

    /** If Android lacks a reliable delta, hide until positions are measured. */
    public void uncertainScroll() {
        scene.uncertainScroll();
    }

    public void setActiveTranslations(java.util.Set<String> ids) { scene.setActiveTranslations(ids); }

    public void clear() { scene.clear(); }

    public void destroy() { scene.destroy(); }
}
