package com.wds.sixstars.contextoverlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * X accessibility overlay renderer. It does no accessibility tree scanning,
 * translation, or layout inference: receives positions from the spatial and
 * change-reaction services. All entry points must execute on the UI thread.
 *
 * MANUAL: exactly one small ⇄ action anchored to the right of each post body.
 * AUTO: delegates touch-through, replacement-style drawing to the separate
 * AutoInlineTranslationRenderer. Manual marker/detail behavior is unchanged.
 */
public final class AccessibilityOverlayRenderer {
    public interface ClickHandler { void onTranslate(TextCandidate candidate); }

    private final Context context;
    private final WindowManager windowManager;
    private final Map<String, Marker> markers = new HashMap<>();
    private final AutoInlineTranslationRenderer autoInline;

    private View detail;
    private WindowManager.LayoutParams detailParams;
    private String detailFingerprint;
    private String detailText;
    private boolean automaticMode;
    private String lastWindowError = "";

    public int visibleBadgeCount() { return markers.size(); }
    public int visibleTranslationCount() { return autoInline.visibleCount(); }

    /** Reattach on a verified X app return or its window-state transition. */
    public void resetAutoWindowAttachment() {
        if (automaticMode) autoInline.resetWindowAttachment();
    }
    public String lastWindowError() {
        String inlineError = autoInline.lastWindowError();
        return inlineError.isEmpty() ? lastWindowError : inlineError;
    }

    public AccessibilityOverlayRenderer(AccessibilityService service) {
        context = service;
        windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        autoInline = new AutoInlineTranslationRenderer(service);
    }

    /** Auto text compositor follows only the verified X application window. */
    public void setAutoTargetWindow(int id, Rect windowScreenBounds) {
        autoInline.setTargetWindow(id, windowScreenBounds);
    }

    /** Backward-compatible manual call for old callers. */
    public void update(List<TextCandidate> candidates, ClickHandler clicks) {
        update(candidates, clicks, false, false);
    }

    public void update(List<TextCandidate> candidates, ClickHandler clicks, boolean automatic) {
        update(candidates, clicks, automatic, false);
    }

    public void update(List<TextCandidate> candidates, ClickHandler clicks,
                       boolean automatic, boolean scrolling) {
        if (automaticMode != automatic) {
            clear();
            automaticMode = automatic;
        }

        if (automatic) {
            removeAllMarkers();
            hideDetail();
            autoInline.reconcile(candidates, scrolling);
        } else {
            autoInline.clear();
            reconcileMarkers(candidates, clicks);
        }

        if (!automatic && detailFingerprint != null) {
            TextCandidate matching = find(candidates, detailFingerprint);
            if (matching == null || !onScreen(matching)) hideDetail();
            else positionDetail(matching);
        }
    }

    private void reconcileMarkers(List<TextCandidate> candidates, ClickHandler clicks) {
        Set<String> present = new HashSet<>();
        for (TextCandidate c : candidates) {
            if (c.authorName || !onScreen(c) || !present.add(c.fingerprint)) continue;
            Marker marker = markers.get(c.fingerprint);
            if (marker == null) {
                final Marker created = new Marker();
                created.candidate = c;
                created.view = badge();
                created.view.setOnClickListener(v -> {
                    if (created.candidate != null) clicks.onTranslate(created.candidate);
                });
                created.params = params(dp(36), dp(26));
                moveMarker(created, c);
                try {
                    windowManager.addView(created.view, created.params);
                    markers.put(c.fingerprint, created);
                    lastWindowError = "";
                } catch (RuntimeException ex) {
                    lastWindowError = ex.getClass().getSimpleName();
                }
            } else {
                marker.candidate = c;
                moveMarker(marker, c);
                try { windowManager.updateViewLayout(marker.view, marker.params); }
                catch (RuntimeException ex) { lastWindowError = ex.getClass().getSimpleName(); }
            }
        }

        for (String key : new HashSet<>(markers.keySet())) {
            if (!present.contains(key)) {
                Marker gone = markers.remove(key);
                if (gone != null) remove(gone.view);
            }
        }
    }

    public void markReady(String fingerprint) {
        Marker marker = markers.get(fingerprint);
        if (marker != null) {
            marker.view.setText("✓");
            marker.view.setContentDescription("Translation prepared. Tap to view.");
        }
    }

    public void showAutoTranslation(TextCandidate candidate, String translation) {
        if (automaticMode) autoInline.show(candidate, translation);
    }

    /** Dispatch measured X motion to the central scene, not WindowManager. */
    public void measuredScroll(int deltaX, int deltaY) {
        if (automaticMode) autoInline.measuredScroll(deltaX, deltaY);
    }

    /** Do not display stale text when the scroll movement wasn't measured. */
    public void uncertainScroll() {
        if (automaticMode) autoInline.uncertainScroll();
    }

    private void moveMarker(Marker marker, TextCandidate candidate) {
        Rect b = candidate.bounds;
        Rect post = candidate.postBounds;
        // Every post shares the same horizontal anchor: right edge of the
        // card, aligned with the first body-text line, not random child nodes.
        marker.params.x = clamp(post.right - dp(44), dp(2), screenWidth() - dp(38));
        marker.params.y = clamp(b.top + dp(2), dp(4), screenHeight() - dp(32));
    }

    private boolean onScreen(TextCandidate c) {
        if (!XPostSpatialAnalyzer.onScreen(c.bounds, screenWidth(), screenHeight()))
            return false;
        // Remove a badge/panel before its last few pixels become a floating
        // artifact at the viewport edge. Larger bodies require only 26 dp
        // still visible; small single-line posts require around 10 dp.
        int visibleTop = Math.max(0, c.bounds.top);
        int visibleBottom = Math.min(screenHeight(), c.bounds.bottom);
        int required = Math.min(dp(26), Math.max(dp(10), c.bounds.height() / 4));
        return visibleBottom - visibleTop >= required;
    }

    public void showDetail(TextCandidate candidate, String message) {
        if (candidate == null || message == null || !onScreen(candidate)) return;
        detailFingerprint = candidate.fingerprint;
        detailText = message;
        if (detail == null) {
            TextView text = new TextView(context);
            text.setTextColor(Color.WHITE);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            text.setPadding(dp(12), dp(9), dp(12), dp(9));
            text.setMaxLines(12);
            text.setBackground(roundBackground(0xF0172F3B, dp(11), 0xFF00BFA5));
            text.setContentDescription("Full translation. Tap to close.");
            text.setOnClickListener(v -> hideDetail());
            detail = text;
            detailParams = params(panelWidth(), WindowManager.LayoutParams.WRAP_CONTENT);
            positionDetail(candidate);
            try { windowManager.addView(text, detailParams); }
            catch (RuntimeException ex) {
                lastWindowError = ex.getClass().getSimpleName();
                detail = null;
                detailParams = null;
            }
        } else {
            positionDetail(candidate);
        }
    }

    private void positionDetail(TextCandidate c) {
        if (detail == null || detailParams == null) return;
        ((TextView) detail).setText(detailText + "\nTap to dismiss");
        detailParams.x = clamp(c.postBounds.left + dp(6), dp(4),
                Math.max(dp(4), screenWidth() - panelWidth() - dp(4)));
        int below = c.bounds.bottom + dp(5);
        // Unlike the old renderer, never clamp an off-screen source to an
        // unrelated visible coordinate. The reactor removes it on departure.
        detailParams.y = below + dp(150) < screenHeight()
                ? below : Math.max(dp(6), c.bounds.top - dp(155));
        try { windowManager.updateViewLayout(detail, detailParams); }
        catch (RuntimeException ignored) { }
    }

    private TextView badge() {
        TextView text = new TextView(context);
        text.setText("⇄");
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setGravity(Gravity.CENTER);
        text.setContentDescription("Translate this X post with PLaMo");
        text.setBackground(roundBackground(0xE0205361, dp(8), 0xFF75E6D5));
        return text;
    }

    private GradientDrawable roundBackground(int fill, int radius, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(radius);
        bg.setStroke(dp(1), stroke);
        return bg;
    }

    private WindowManager.LayoutParams params(int width, int height) {
        WindowManager.LayoutParams value = new WindowManager.LayoutParams(
                width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        value.gravity = Gravity.TOP | Gravity.LEFT;
        value.setTitle("Roots local X translation");
        return value;
    }

    public void hideDetail() {
        if (detail != null) remove(detail);
        detail = null;
        detailParams = null;
        detailFingerprint = null;
        detailText = null;
    }

    private void removeAllMarkers() {
        for (Marker marker : markers.values()) remove(marker.view);
        markers.clear();
    }

    public void clear() {
        hideDetail();
        removeAllMarkers();
        autoInline.clear();
    }

    /** Called once when the accessibility host is destroyed. */
    public void destroy() {
        clear();
        autoInline.destroy();
    }

    private void remove(View view) {
        try { windowManager.removeViewImmediate(view); }
        catch (RuntimeException ignored) { }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private int screenWidth() {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return metrics.widthPixels;
    }

    private int screenHeight() {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return metrics.heightPixels;
    }

    private int panelWidth() {
        return Math.min(dp(300), screenWidth() - dp(12));
    }

    private static TextCandidate find(List<TextCandidate> candidates, String fingerprint) {
        for (TextCandidate c : candidates)
            if (c.fingerprint.equals(fingerprint)) return c;
        return null;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, Math.max(maximum, minimum)));
    }

    private static final class Marker {
        TextCandidate candidate;
        TextView view;
        WindowManager.LayoutParams params;
    }


}
