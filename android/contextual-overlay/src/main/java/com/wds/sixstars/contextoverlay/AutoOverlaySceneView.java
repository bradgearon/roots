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

        /** Equality of the pixels this item will paint, independent of a
         * transient candidate ID or another occurrence's layout cache. */
        boolean samePaint(TextItem other) {
            if(other==null||x!=other.x||y!=other.y||width!=other.width||
                height!=other.height||fillColor!=other.fillColor)return false;
            if(layout==other.layout)return true;
            if(layout==null||other.layout==null||
                !android.text.TextUtils.equals(layout.getText(),other.layout.getText())||
                layout.getWidth()!=other.layout.getWidth()||layout.getHeight()!=other.layout.getHeight()||
                layout.getLineCount()!=other.layout.getLineCount()||
                layout.getPaint().getColor()!=other.layout.getPaint().getColor()||
                layout.getPaint().getTextSize()!=other.layout.getPaint().getTextSize()||
                layout.getPaint().isFakeBoldText()!=other.layout.getPaint().isFakeBoldText())return false;
            for(int line=0;line<layout.getLineCount();line++)
                if(layout.getLineStart(line)!=other.layout.getLineStart(line)||
                    layout.getLineEnd(line)!=other.layout.getLineEnd(line)||
                    layout.getLineTop(line)!=other.layout.getLineTop(line)||
                    layout.getEllipsisStart(line)!=other.layout.getEllipsisStart(line)||
                    layout.getEllipsisCount(line)!=other.layout.getEllipsisCount(line))return false;
            return true;
        }

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
