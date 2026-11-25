package com.wds.sixstars.contextoverlay;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.List;

/**
 * Swappable compositor content strategy. The surface lifecycle, X acquisition,
 * translation cache and frame scheduler do not depend on the presentation.
 *
 * SELECTIVE (now): paint only translated text on a transparent backing.
 * FULL_REPLACEMENT (later): supply another implementation with a richer
 * semantic scene snapshot and its own scroll/interaction controller.
 */
interface AutoScenePresentation {
    enum Mode { SELECTIVE_REPLACEMENT, COMPLETE_REPLACEMENT }

    /** A future full-feed template supplies another AutoScenePresentation. */
    static AutoScenePresentation create(Mode mode) {
        if (mode == Mode.SELECTIVE_REPLACEMENT) return new Selective();
        throw new UnsupportedOperationException(
                "Complete replacement requires the semantic X feed/interaction adapter");
    }

    void draw(Canvas canvas, List<AutoOverlaySceneView.TextItem> items,
              int originX, int originY);

    /** Current automatic mode preserves X's scrolling and touch handling. */
    final class Selective implements AutoScenePresentation {
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas canvas,
                List<AutoOverlaySceneView.TextItem> items, int originX, int originY) {
            int root = canvas.save();
            canvas.translate(-originX, -originY);
            for (AutoOverlaySceneView.TextItem item : items) {
                if (item.layout == null || item.width <= 0 || item.height <= 0) continue;
                int saved = canvas.save();
                canvas.clipRect(item.x, item.y, item.x + item.width,
                        item.y + item.height);
                background.setColor(item.fillColor);
                canvas.drawRect(item.x, item.y, item.x + item.width,
                        item.y + item.height, background);
                canvas.translate(item.x, item.y);
                item.layout.draw(canvas);
                canvas.restoreToCount(saved);
            }
            canvas.restoreToCount(root);
        }
    }
}
