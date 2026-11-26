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
    private static final int MAX_VISIBLE = 8;

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

    void resetWindowAttachment() {
        if (destroyed) return;
        surfaces.resetAttachment();
        frames.request();
    }

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
        long nowMs = SystemClock.uptimeMillis();
        HashSet<String> seen = new HashSet<>();
        for (TextCandidate candidate : candidates) {
            boolean translatable = AutoTranslationEligibilityService.accepts(
                    candidate, preferences.targetLanguage());
            boolean clippedName = candidate.author != null
                    && candidate.author.truncated;
            // A clipped author may need its left-side details badge even if
            // the post body is already in the target language.
            boolean previouslyVerifiedName =
                    positions.hasTrackedAuthor(candidate.fingerprint, nowMs);
            // A temporary X header omission must not make an already
            // identified name disappear, even on an English/short tweet.
            if ((!translatable && !clippedName && !previouslyVerifiedName
                    && !translations.containsKey(candidate.fingerprint))
                    || !seen.add(candidate.fingerprint)) continue;
            AutoInlinePlacementService.Placement rectangle =
                    AutoInlinePlacementService.locate(
                            candidate.bounds.left, candidate.bounds.top,
                            candidate.bounds.right, candidate.bounds.bottom,
                            screen.widthPixels, screen.heightPixels, screen.density);
            if (rectangle != null) {
                Rect avatar = candidate.avatarBounds;
                AutoDetailsPlacementService.Placement tweetDetails =
                        AutoDetailsPlacementService.locateTweet(
                                candidate.bounds.left, candidate.bounds.top,
                                candidate.bounds.right, candidate.bounds.bottom,
                                candidate.postBounds.right, screen.widthPixels,
                                screen.heightPixels, screen.density);
                AutoDetailsPlacementService.Placement nameDetails = null;
                String fullName = null;
                if (candidate.author != null && candidate.author.truncated) {
                    fullName = candidate.author.displayName;
                    nameDetails = AutoDetailsPlacementService.locateName(
                            candidate.bounds.left, candidate.bounds.top,
                            candidate.bounds.right, candidate.bounds.bottom,
                            candidate.postBounds.left, candidate.postBounds.top,
                            candidate.postBounds.right, candidate.postBounds.bottom,
                            candidate.hasPostContainer,
                            candidate.author.avatarBounds.left,
                            candidate.author.avatarBounds.top,
                            candidate.author.avatarBounds.right,
                            candidate.author.avatarBounds.bottom,
                            screen.widthPixels, screen.heightPixels, screen.density);
                }
                observations.add(new AutoOverlayPositionStore.Observation(
                        candidate.fingerprint, rectangle, tweetDetails,
                        nameDetails, fullName));
            }
            if (observations.size() >= MAX_VISIBLE) break;
        }

        if (scrolling) expanded.hide();
        long previousVersion = positions.version();
        positions.publish(observations, scrolling, nowMs);
        // A missing stationary post gets 1.1 seconds' grace. There may be no
        // later accessibility event, so expire it with ONE delayed frame.
        main.removeCallbacks(pruneMissing);
        long nextExpiry = positions.nextExpiryDelayMs(nowMs);
        if (nextExpiry >= 0L) main.postDelayed(pruneMissing, nextExpiry);
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

        // Shared canvas paints ONLY the source bodies. Names/avatars and
        // action badges remain separate semantic roles.
        for (AutoOverlayPositionStore.Located item : located) {
            if (item.nameDetails != null && item.authorName != null) {
                present.add(item.id);
                ensureNameAction(item.id, item.authorName, item.nameDetails, palette);
            } else {
                removeNameAction(item.id);
            }

            TranslationState state = translations.get(item.id);
            if (state == null || state.translation == null) {
                removeTweetAction(item.id);
                continue;
            }
            state.prepareLayout(item.rectangle, palette);
            present.add(item.id);
            painted.add(new AutoOverlaySceneView.TextItem(
                    item.rectangle.x, item.rectangle.y,
                    item.rectangle.width, item.rectangle.height,
                    palette.background, state.inlineLayout));

            if (state.needsMore && item.details != null)
                ensureTweetAction(item.id, item.details, palette);
            else
                removeTweetAction(item.id);
        }

        // The scene is one touchless window-attached GPU surface (API 34+),
        // or the existing FLAG_NOT_TOUCHABLE WindowManager fallback.
        surfaces.present(painted);
        for (String key : new HashSet<>(controls.keySet())) {
            if (!present.contains(key)) removeControls(key);
        }
        visibleCount = painted.size();
    }

    private Controls controlsFor(String id) {
        Controls existing = controls.get(id);
        if (existing == null) {
            existing = new Controls();
            controls.put(id, existing);
        }
        return existing;
    }

    /** NAME action: underneath the actual avatar, only if X clips its name. */
    private void ensureNameAction(String id, String name,
            AutoDetailsPlacementService.Placement placement,
            AutoInlineThemeService.Palette palette) {
        Controls action = controlsFor(id);
        action.nameText = name;
        if (action.name == null) {
            ImageView view = actionView(android.R.drawable.ic_menu_info_details,
                    "Read full account name", palette);
            WindowManager.LayoutParams params = params(
                    placement.width, placement.height, false);
            params.setTitle("Roots clipped author name");
            view.setOnClickListener(v -> {
                Controls current = controls.get(id);
                if (current == null || current.nameText == null) return;
                expanded.toggleName(id, current.nameText);
                if (expanded.isShowing("name:" + id)) raiseAction(view, params);
            });
            place(params, placement);
            try {
                windowManager.addView(view, params);
                action.name = view;
                action.nameParams = params;
                action.lastName = placement;
                lastError = "";
            } catch (RuntimeException error) {
                lastError = error.getClass().getSimpleName();
                remove(view);
            }
        } else if (!placement.sameAs(action.lastName)) {
            place(action.nameParams, placement);
            action.lastName = placement;
            try {
                windowManager.updateViewLayout(action.name, action.nameParams);
            } catch (RuntimeException error) {
                lastError = error.getClass().getSimpleName();
                removeNameAction(id);
            }
        }
    }

    /** TWEET action: thumb-side RIGHT and only for a clipped translation. */
    private void ensureTweetAction(String id,
            AutoDetailsPlacementService.Placement placement,
            AutoInlineThemeService.Palette palette) {
        Controls action = controlsFor(id);
        if (action.tweet == null) {
            ImageView view = actionView(android.R.drawable.ic_menu_search,
                    "Read full translated tweet", palette);
            WindowManager.LayoutParams params = params(
                    placement.width, placement.height, false);
            params.setTitle("Roots clipped tweet details");
            view.setOnClickListener(v -> {
                TranslationState state = translations.get(id);
                if (state == null || state.translation == null) return;
                expanded.toggleTweet(id, state.translation);
                if (expanded.isShowing("tweet:" + id)) raiseAction(view, params);
            });
            place(params, placement);
            try {
                windowManager.addView(view, params);
                action.tweet = view;
                action.tweetParams = params;
                action.lastTweet = placement;
                lastError = "";
            } catch (RuntimeException error) {
                lastError = error.getClass().getSimpleName();
                remove(view);
            }
        } else if (!placement.sameAs(action.lastTweet)) {
            place(action.tweetParams, placement);
            action.lastTweet = placement;
            try {
                windowManager.updateViewLayout(action.tweet, action.tweetParams);
            } catch (RuntimeException error) {
                lastError = error.getClass().getSimpleName();
                removeTweetAction(id);
            }
        }
    }

    private ImageView actionView(int icon, String description,
            AutoInlineThemeService.Palette palette) {
        ImageView view = new ImageView(context);
        view.setImageResource(icon);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setPadding(dp(6), dp(6), dp(6), dp(6));
        view.setContentDescription(description);
        view.setFocusable(false);
        view.setClickable(true);
        styleDetails(view, palette);
        return view;
    }

    private void raiseAction(View view, WindowManager.LayoutParams params) {
        // Keep the pressed icon above the expansion sheet so another tap
        // can close it, without making the whole overlay touchable.
        try {
            windowManager.removeViewImmediate(view);
            windowManager.addView(view, params);
        } catch (RuntimeException error) {
            lastError = error.getClass().getSimpleName();
        }
    }

    private static void place(WindowManager.LayoutParams params,
            AutoDetailsPlacementService.Placement placement) {
        params.x = placement.x;
        params.y = placement.y;
        params.width = placement.width;
        params.height = placement.height;
    }

    private void removeNameAction(String id) {
        Controls action = controls.get(id);
        if (action == null || action.name == null) return;
        remove(action.name);
        action.name = null;
        action.nameParams = null;
        action.lastName = null;
        action.nameText = null;
        expanded.hideIfSource("name:" + id);
        if (action.tweet == null) controls.remove(id);
    }

    private void removeTweetAction(String id) {
        Controls action = controls.get(id);
        if (action == null || action.tweet == null) return;
        remove(action.tweet);
        action.tweet = null;
        action.tweetParams = null;
        action.lastTweet = null;
        expanded.hideIfSource("tweet:" + id);
        if (action.name == null) controls.remove(id);
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
        removeTweetAction(id);
        removeNameAction(id);
        controls.remove(id);
        expanded.hideIfSource(id);
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
        boolean needsMore;

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

            int fullWidth = Math.max(1, p.width - dp(4));
            int maxLines = Math.max(1, p.height / dp(18));
            StaticLayout full = StaticLayout.Builder.obtain(
                        translation, 0, translation.length(), paint, fullWidth)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.09f)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build();
            needsMore = full.getHeight() > p.height || full.getLineCount() > maxLines;
            // Reserve the right thumb-side control without obscuring text.
            int displayWidth = Math.max(1, fullWidth - (needsMore ? dp(41) : 0));
            inlineLayout = StaticLayout.Builder.obtain(
                        translation, 0, translation.length(), paint, displayWidth)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.09f)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(maxLines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(displayWidth)
                    .build();
            cachedWidth = p.width;
            cachedHeight = p.height;
            cachedColor = palette.foreground;
        }
    }


    /** Two independent semantic actions, keyed by the SAME post fingerprint. */
    private static final class Controls {
        ImageView name;
        ImageView tweet;
        WindowManager.LayoutParams nameParams;
        WindowManager.LayoutParams tweetParams;
        AutoDetailsPlacementService.Placement lastName;
        AutoDetailsPlacementService.Placement lastTweet;
        String nameText;
    }
}
