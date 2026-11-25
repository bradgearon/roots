package com.wds.sixstars.contextoverlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Presentation attachment, NOT an accessibility reader.
 *
 * API 34+: transparent, non-input SurfaceControl attached to the VERIFIED
 * X application window. This avoids a full-screen SurfaceControlViewHost
 * input channel that could steal the underlying app's touch gestures.
 *
 * API 26-33 or attachment failure: existing FLAG_NOT_TOUCHABLE WindowManager
 * View with identical scene painting and positions.
 *
 * The scene painter is injected: switching to a full replacement later does
 * not require changing the transport, translation coordinator or acquisition.
 */
final class AutoSceneSurfaceManager {
    private final AccessibilityService service;
    private final WindowManager wm;
    private final AutoScenePresentation presentation;
    private AutoWindowTarget target;
    private AutoWindowAttachedSurface attached;
    private AutoOverlaySceneView fallback;
    private boolean attachFailed;
    private String lastError = "";
    private List<AutoOverlaySceneView.TextItem> lastPaint = Collections.emptyList();

    AutoSceneSurfaceManager(AccessibilityService service, AutoScenePresentation presentation) {
        this.service = service;
        this.presentation = presentation;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    String lastError() { return lastError; }

    /** Returns true only when the verified X window geometry/identity changed. */
    boolean setTarget(AutoWindowTarget next) {
        if (next != null && !next.isValid()) next = null;
        boolean changed = target == null ? next != null
                : !target.samePosition(next);
        if (!changed) return false;
        if (next == null) {
            releaseAttached();
            target = null;
            attachFailed = false;
            return true;
        }
        if (target == null || !target.sameSurface(next)) {
            releaseAttached();
            attachFailed = false;
        }
        target = next;
        return true;
    }

    void present(List<AutoOverlaySceneView.TextItem> items) {
        lastPaint = Collections.unmodifiableList(new ArrayList<>(items));
        if (attached != null) {
            try {
                if (items.isEmpty()) attached.present(items);
                else attached.present(items, target);
                return;
            } catch (RuntimeException error) {
                attachFailed = true;
                lastError = "Window renderer fell back: " + error.getClass().getSimpleName();
                releaseAttached();
            }
        }
        if (!items.isEmpty() && !attachFailed
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && AutoSceneAttachmentPolicy.canAttach(
                        Build.VERSION.SDK_INT, target)) {
            try {
                final int attachedId = target.windowId;
                AutoWindowAttachedSurface created = new AutoWindowAttachedSurface(
                        service, target, presentation,
                        () -> onAttachedFailure(attachedId));
                attached = created;
                created.present(items, target);
                removeFallback();
                lastError = "";
                return;
            } catch (RuntimeException | LinkageError error) {
                attachFailed = true;
                lastError = "Window surface unavailable; using touch-through fallback: "
                        + error.getClass().getSimpleName();
                releaseAttached();
            }
        }
        if (items.isEmpty()) {
            if (fallback != null) fallback.setTextItems(items);
            return;
        }
        if (fallback == null) {
            AutoOverlaySceneView view = new AutoOverlaySceneView(service);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.setTitle("Roots selective translation fallback");
            try {
                wm.addView(view, params);
                fallback = view;
            } catch (RuntimeException error) {
                lastError = "Fallback surface: " + error.getClass().getSimpleName();
                return;
            }
        }
        fallback.setTextItems(items);
    }

    private void onAttachedFailure(int originalWindowId) {
        // Fail closed: never keep a broken surface above X and never adopt
        // a callback from a previous application's window session.
        if (attached == null || target == null
                || target.windowId != originalWindowId) return;
        attachFailed = true;
        lastError = "GPU window surface failed; restored touch-through fallback";
        releaseAttached();
        present(lastPaint);
    }

    private void releaseAttached() {
        if (attached != null) {
            attached.close();
            attached = null;
        }
    }

    private void removeFallback() {
        if (fallback == null) return;
        try { wm.removeViewImmediate(fallback); } catch (RuntimeException ignored) { }
        fallback = null;
    }

    void clear() {
        releaseAttached();
        removeFallback();
        target = null;
        attachFailed = false;
        lastError = "";
        lastPaint = Collections.emptyList();
    }
}
