package com.wds.sixstars.contextoverlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only AUTO renderer and WindowManager owner. Accessibility snapshots,
 * measured scroll deltas and completed translations arrive as DATA; NONE of
 * those callbacks directly draws a view or performs WindowManager operations.
 *
 * Choreographer coalesces updates into a single vsync frame. All translated
 * text is drawn by ONE shared, touch-through Canvas surface. Each post has
 * one small, separately touchable DETAILS control below its avatar.
 *
 * The translated text preserves the restored visual baseline. The single
 * avatar-gutter details icon toggles the existing on-demand bottom sheet.
 */
final class AutoOverlaySceneController {
    private static final int MAX_STORED_TRANSLATIONS = 96;
    private static final int MAX_VISIBLE = 5;

    private final Context context;
    private final WindowManager windowManager;
    private final OverlayPreferences preferences;
    private final AutoOverlayPositionStore positions;
    private final AutoOverlayFrameScheduler frames;
    private final AutoTranslationExpansionRenderer expanded;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, TranslationState> translations =
            new LinkedHashMap<String, TranslationState>(32, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TranslationState> eldest) {
                    return size() > MAX_STORED_TRANSLATIONS;
                }
            };
    private final Map<String, Controls> controls = new HashMap<>();
    private final AutoSceneSurfaceManager surfaces;
    private String lastError = "";
    private int visibleCount;
    private boolean destroyed;

    AutoOverlaySceneController(AccessibilityService service) {
        context = service;
        windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        preferences = new OverlayPreferences(service);
        DisplayMetrics display = context.getResources().getDisplayMetrics();
        positions = new AutoOverlayPositionStore(display.widthPixels,
                display.heightPixels, statusBarHeight());
        frames = new AutoOverlayFrameScheduler(this::renderFrame);
        surfaces = new AutoSceneSurfaceManager(service, AutoScenePresentation.create(AutoScenePresentation.Mode.SELECTIVE_REPLACEMENT));
        expanded = new AutoTranslationExpansionRenderer(service);
    }

    int visibleCount() { return visibleCount; }

    String lastError() {
        String expansionError = expanded.lastWindowError();
        if (!expansionError.isEmpty()) return expansionError;
        String surfaceError = surfaces.lastError();
        return surfaceError.isEmpty() ? lastError : surfaceError;
    }

    /** Only the verified foreground X application window may host our surface. */
    void setTargetWindow(int windowId, Rect bounds) {
        if (destroyed) return;
        boolean changed = surfaces.setTarget(
                bounds == null ? null : new AutoWindowTarget(windowId, bounds));
        // A moved/resized X window changes our screen-to-window transform.
        if (changed) frames.request();
    }

    /** Accept immutable post positions. NEVER called by the frame callback. */
    void publishPositions(List<TextCandidate> candidates, boolean scrolling) {
        if (destroyed) return;
        ArrayList<AutoOverlayPositionStore.Observation> observations = new ArrayList<>();
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        HashSet<String> seen = new HashSet<>();
        for (TextCandidate candidate : candidates) {
            if (!AutoTranslationEligibilityService.accepts(
                    candidate, preferences.targetLanguage()) ||
                    !seen.add(candidate.fingerprint)) continue;
            AutoInlinePlacementService.Placement rectangle =
                    AutoInlinePlacementService.locate(
                            candidate.bounds.left, candidate.bounds.top,
                            candidate.bounds.right, candidate.bounds.bottom,
                            screen.widthPixels, screen.heightPixels, screen.density);
            if (rectangle != null) {
                Rect avatar = candidate.avatarBounds;
                AutoDetailsPlacementService.Placement details =
                        AutoDetailsPlacementService.locate(
                                candidate.bounds.left, candidate.bounds.top,
                                candidate.bounds.right, candidate.bounds.bottom,
                                candidate.postBounds.left, candidate.postBounds.top,
                                candidate.postBounds.right, candidate.postBounds.bottom,
                                candidate.hasPostContainer,
                                avatar.left, avatar.top, avatar.right, avatar.bottom,
                                screen.widthPixels, screen.heightPixels, screen.density);
                observations.add(new AutoOverlayPositionStore.Observation(
                        candidate.fingerprint, rectangle, details));
            }
            if (observations.size() >= MAX_VISIBLE) break;
        }

        if (scrolling) expanded.hide();
        long previousVersion = positions.version();
        positions.publish(observations, scrolling, SystemClock.uptimeMillis());
        // A missing stationary post gets 1.1 seconds' grace. There may be no
        // later accessibility event, so expire it with ONE delayed frame.
        main.removeCallbacks(pruneMissing);
        if (observations.size() < controls.size())
            main.postDelayed(pruneMissing,
                    AutoOverlayPositionStore.TRANSIENT_GAP_MS + 1);
        if (previousVersion != positions.version()) frames.request();
    }

    private final Runnable pruneMissing = () -> {
        if (!destroyed) requestNextFrame();
    };

    private void requestNextFrame() {
        if (!destroyed) {
            frames.request();
        }
    }

    /** A measured scroll delta from X; positive Y means content moved up. */
    void measuredScroll(int deltaX, int deltaY) {
        if (destroyed) return;
        expanded.hide();
        positions.applyMeasuredScroll(deltaX, deltaY);
        frames.request();
    }

    /** No reliable delta reported: hide until the next measured snapshot. */
    void uncertainScroll() {
        if (destroyed) return;
        expanded.hide();
        positions.suspendUntilMeasured();
        frames.request();
    }

    /** A translation arrives without any AccessibilityService tree scan. */
    void publishTranslation(String fingerprint, String translation) {
        if (destroyed || fingerprint == null || translation == null ||
                translation.trim().isEmpty() || !positions.contains(fingerprint)) return;
        TranslationState state = translations.get(fingerprint);
        if (state == null) {
            state = new TranslationState(fingerprint);
            translations.put(fingerprint, state);
        }
        String normalized = translation.trim();
        if (!normalized.equals(state.translation)) {
            state.translation = normalized;
            state.invalidateLayout();
        }
        frames.request();
    }

    /** Only the Choreographer callback enters this method. */
    private void renderFrame() {
        if (destroyed) return;
        List<AutoOverlayPositionStore.Located> located =
                positions.snapshot(SystemClock.uptimeMillis());
        ArrayList<AutoOverlaySceneView.TextItem> painted = new ArrayList<>();
        Set<String> present = new HashSet<>();
        AutoInlineThemeService.Palette palette = AutoInlineThemeService.resolve(
                context, preferences.inlineTheme());

        for (AutoOverlayPositionStore.Located item : located) {
            TranslationState state = translations.get(item.id);
            if (state == null || state.translation == null) continue;
            AutoInlinePlacementService.Placement rectangle = item.rectangle;
            state.prepareLayout(rectangle, palette);
            present.add(item.id);
            painted.add(new AutoOverlaySceneView.TextItem(
                    rectangle.x, rectangle.y, rectangle.width, rectangle.height,
                    palette.background, state.inlineLayout));
        }

        // One rendering surface owns all translated body pixels. On API 34+
        // its GPU buffer is attached to the X window with no input channel.
        surfaces.present(painted);

        for (AutoOverlayPositionStore.Located item : located) {
            TranslationState state = translations.get(item.id);
            if (state != null && state.translation != null)
                ensureDetails(item.id, state, item.details, palette);
        }

        for (String key : new HashSet<>(controls.keySet())) {
            if (!present.contains(key)) removeControls(key);
        }

        visibleCount = present.size();
    }

    /** Exactly one role-aware control for each translated post, not the name row. */
    private void ensureDetails(String id, TranslationState state,
            AutoDetailsPlacementService.Placement placement,
            AutoInlineThemeService.Palette palette) {
        if (placement == null) {
            removeControls(id);
            return;
        }
        Controls existing = controls.get(id);
        if (existing == null) {
            Controls action = new Controls();
            action.details = new ImageView(context);
            action.details.setImageResource(android.R.drawable.ic_menu_search);
            action.details.setScaleType(ImageView.ScaleType.FIT_CENTER);
            action.details.setPadding(dp(6), dp(6), dp(6), dp(6));
            action.details.setContentDescription("Read more: show full X post translation");
            action.details.setFocusable(false);
            action.details.setClickable(true);
            action.detailsParams = params(placement.width, placement.height, false);
            action.detailsParams.setTitle("Roots post details action");
            styleDetails(action.details, palette);
            action.details.setOnClickListener(v -> {
                TranslationState current = translations.get(id);
                if (current == null || current.translation == null) return;
                // The same magnifier OPENS and CLOSES the full translation.
                expanded.toggle(id, current.translation);
                if (expanded.isShowing(id)) {
                    raiseDetails(id); // keep it tappable above the bottom sheet
                }
                frames.request();
            });
            applyDetailsCoordinates(action, placement);
            try {
                windowManager.addView(action.details, action.detailsParams);
                controls.put(id, action);
                lastError = "";
            } catch (RuntimeException error) {
                lastError = error.getClass().getSimpleName();
                remove(action.details);
            }
        } else if (!existing.lastPlacement.sameAs(placement)) {
            applyDetailsCoordinates(existing, placement);
            try {
                windowManager.updateViewLayout(existing.details, existing.detailsParams);
            } catch (RuntimeException error) {
                lastError = error.getClass().getSimpleName();
                removeControls(id);
            }
        }
    }

    private void raiseDetails(String id) {
        Controls action = controls.get(id);
        if (action == null) return;
        // Expansion is added after the control. Re-add the tiny hit target so
        // a second tap still closes the sheet when a post is near the bottom.
        try {
            windowManager.removeViewImmediate(action.details);
            windowManager.addView(action.details, action.detailsParams);
        } catch (RuntimeException error) {
            lastError = error.getClass().getSimpleName();
            removeControls(id);
        }
    }

    private static void applyDetailsCoordinates(Controls action,
            AutoDetailsPlacementService.Placement p) {
        action.detailsParams.x = p.x;
        action.detailsParams.y = p.y;
        action.detailsParams.width = p.width;
        action.detailsParams.height = p.height;
        action.lastPlacement = p;
    }

    private void styleDetails(ImageView details, AutoInlineThemeService.Palette palette) {
        details.setColorFilter(palette.foreground);
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.background);
        background.setCornerRadius(dp(6));
        background.setStroke(dp(1), palette.controlForeground);
        details.setBackground(background);
    }

    private WindowManager.LayoutParams params(int width, int height, boolean touchThrough) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (touchThrough) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        }
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        return p;
    }

    /** Keep the overlay below system status icons during an upward fling. */
    private int statusBarHeight() {
        int resource = context.getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        return resource == 0 ? dp(24) :
                context.getResources().getDimensionPixelSize(resource);
    }

    private int dp(int value) {
        return Math.max(1, Math.round(value *
                context.getResources().getDisplayMetrics().density));
    }

    private float sp(int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics());
    }

    private void removeControls(String id) {
        Controls buttons = controls.remove(id);
        if (buttons == null) return;
        expanded.hideIfSource(id);
        remove(buttons.details);
    }

    private void remove(View view) {
        if (view == null) return;
        try { windowManager.removeViewImmediate(view); }
        catch (RuntimeException ignored) { }
    }

    void clear() {
        main.removeCallbacks(pruneMissing);
        expanded.hide();
        positions.clear();
        for (String id : new HashSet<>(controls.keySet())) removeControls(id);
        visibleCount = 0;
        surfaces.clear();
        // Preserve only bounded in-memory translations and user choices;
        // the source geometry is cleared whenever X leaves the foreground.
    }

    void destroy() {
        destroyed = true;
        clear();
        frames.stop();
        translations.clear();
    }

    private final class TranslationState {
        final String id;
        String translation;
        int cachedWidth = -1;
        int cachedHeight = -1;
        int cachedColor;
        StaticLayout inlineLayout;

        TranslationState(String id) { this.id = id; }

        void invalidateLayout() {
            cachedWidth = -1;
            inlineLayout = null;
        }

        void prepareLayout(AutoInlinePlacementService.Placement p,
                AutoInlineThemeService.Palette palette) {
            if (inlineLayout != null && cachedWidth == p.width &&
                    cachedHeight == p.height && cachedColor == palette.foreground)
                return;

            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(sp(15));
            paint.setColor(palette.foreground);

            // There are no longer any controls inside the translated body;
            // preserve the original 15sp typography but use its full width.
            int textWidth = Math.max(1, p.width - dp(4));
            int maxLines = Math.max(1, p.height / dp(18));
            inlineLayout = StaticLayout.Builder.obtain(
                        translation, 0, translation.length(), paint, textWidth)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.09f)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(maxLines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(textWidth)
                    .build();
            cachedWidth = p.width;
            cachedHeight = p.height;
            cachedColor = palette.foreground;
        }
    }

    /** A detail action is always linked to exactly one post fingerprint. */
    private static final class Controls {
        ImageView details;
        WindowManager.LayoutParams detailsParams;
        AutoDetailsPlacementService.Placement lastPlacement;
    }
}
