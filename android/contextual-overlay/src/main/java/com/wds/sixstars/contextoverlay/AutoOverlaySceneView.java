package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.text.StaticLayout;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ONE touch-through, hardware-accelerated canvas for all AUTO translated
 * post text. Only the frame controller supplies immutable ready-to-draw items.
 *
 * Not an accessibility scanner, text selector, translation client, or window
 * positioning engine. A transparent screen-wide accessibility window owns this
 * View; its painted rectangles are confined to the source text bounds.
 */
final class AutoOverlaySceneView extends View {
    static final class TextItem {
        final int x, y, width, height, fillColor;
        final StaticLayout layout;

        TextItem(int x, int y, int width, int height, int fillColor,
                StaticLayout layout) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.fillColor = fillColor;
            this.layout = layout;
        }
    }

    private final AutoScenePresentation presentation = new AutoScenePresentation.Selective();
    private List<TextItem> texts = Collections.emptyList();

    AutoOverlaySceneView(Context context) {
        super(context);
        setWillNotDraw(false);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentDescription("Roots touch-through translated text layer");
        // This view is never clickable. The actual WindowManager surface is
        // FLAG_NOT_TOUCHABLE; touch actions live in small separate windows.
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setTextItems(List<TextItem> items) {
        texts = Collections.unmodifiableList(new ArrayList<>(items));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        presentation.draw(canvas, texts, 0, 0);
    }
}
