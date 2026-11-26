package com.wds.sixstars.contextoverlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Opt-in, short-lived full-translation sheet for Auto inline mode.
 * Opens only when the user taps the avatar-gutter details icon.
 *
 * Unlike always-visible translation cards, it never changes the X layout or
 * blocks the engagement row during normal scrolling. Outside touches pass to
 * X and dismiss the sheet; a Close button is always visible.
 */
final class AutoTranslationExpansionRenderer {
    private final Context context;
    private final WindowManager windowManager;
    private final OverlayPreferences preferences;
    private View window;
    private String sourceFingerprint;
    private String lastError = "";

    AutoTranslationExpansionRenderer(AccessibilityService service) {
        context = service;
        windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        preferences = new OverlayPreferences(service);
    }

    String lastWindowError() { return lastError; }

    boolean isShowing(String fingerprint) {
        return window != null && fingerprint != null
                && fingerprint.equals(sourceFingerprint);
    }

    void toggleTweet(String fingerprint, String translation) {
        toggle("tweet:" + fingerprint, translation, "Full tweet translation");
    }

    void toggleName(String fingerprint, String displayName) {
        toggle("name:" + fingerprint, displayName, "Full account name");
    }

    /** Legacy entry points remain for callers using the old single action. */
    void toggle(String fingerprint, String translation) {
        toggleTweet(fingerprint, translation);
    }

    private void toggle(String key, String text, String title) {
        if (isShowing(key)) hide();
        else show(key, text, title);
    }

    void show(String fingerprint, String translation) {
        show(fingerprint, translation, "Full translation");
    }

    private void show(String fingerprint, String translation, String heading) {
        hide();
        if (fingerprint == null || translation == null || translation.trim().isEmpty())
            return;

        DisplayMetrics display = context.getResources().getDisplayMetrics();
        int width = Math.min(dp(378), display.widthPixels - dp(20));
        if (width <= dp(100)) return;

        AutoInlineThemeService.Palette palette = AutoInlineThemeService.resolve(
                context, preferences.inlineTheme());
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(13), dp(9), dp(13), dp(12));
        root.setBackground(background(palette.background, palette.controlForeground, 13));
        root.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                hide();
                return true;
            }
            return false;
        });

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(heading);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(palette.foreground);
        header.addView(title, new LinearLayout.LayoutParams(
                0, dp(34), 1f));

        TextView close = new TextView(context);
        close.setText("✕  Close");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        close.setTextColor(palette.foreground);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close full translation");
        close.setOnClickListener(v -> hide());
        header.addView(close, new LinearLayout.LayoutParams(dp(78), dp(34)));
        root.addView(header);

        TextView paragraph = new TextView(context);
        paragraph.setText(translation);
        paragraph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        paragraph.setTextColor(palette.foreground);
        paragraph.setIncludeFontPadding(false);
        paragraph.setLineSpacing(dp(2), 1.04f);
        paragraph.setPadding(0, dp(4), 0, dp(4));
        paragraph.setContentDescription("Full PLaMo translation");
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.addView(paragraph, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        int paragraphWidth = Math.max(1, width - dp(26));
        int textHeight;
        try {
            StaticLayout layout = StaticLayout.Builder.obtain(translation, 0,
                            translation.length(), paragraph.getPaint(), paragraphWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(dp(2), 1.04f)
                    .build();
            textHeight = layout.getHeight();
        } catch (RuntimeException ignored) {
            textHeight = dp(180);
        }

        int scrollHeight = Math.min(
                Math.min(dp(360), display.heightPixels * 55 / 100),
                Math.max(dp(54), textHeight + dp(14)));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, scrollHeight));

        int totalHeight = scrollHeight + dp(57);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, totalHeight, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = Math.max(0, (display.widthPixels - width) / 2);
        params.y = Math.max(dp(10), display.heightPixels - totalHeight - dp(22));
        params.setTitle("Roots full PLaMo translation");

        try {
            windowManager.addView(root, params);
            window = root;
            sourceFingerprint = fingerprint;
            lastError = "";
        } catch (RuntimeException error) {
            lastError = error.getClass().getSimpleName();
            window = null;
            sourceFingerprint = null;
        }
    }

    void hideIfSource(String fingerprint) {
        if (fingerprint != null && (fingerprint.equals(sourceFingerprint)
                || ("name:" + fingerprint).equals(sourceFingerprint)
                || ("tweet:" + fingerprint).equals(sourceFingerprint))) hide();
    }

    void hide() {
        View previous = window;
        window = null;
        sourceFingerprint = null;
        if (previous != null) {
            try { windowManager.removeViewImmediate(previous); }
            catch (RuntimeException ignored) { }
        }
    }

    private GradientDrawable background(int fill, int outline, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), outline);
        return shape;
    }

    private int dp(int n) {
        return Math.max(1, Math.round(n *
                context.getResources().getDisplayMetrics().density));
    }
}
