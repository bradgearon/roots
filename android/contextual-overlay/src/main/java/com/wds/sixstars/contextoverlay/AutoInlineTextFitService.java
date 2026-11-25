package com.wds.sixstars.contextoverlay;

import android.text.Layout;
import android.text.StaticLayout;
import android.widget.TextView;

/** Calculates whether a complete translated paragraph fits the source bounds. */
public final class AutoInlineTextFitService {
    private AutoInlineTextFitService() { }

    public static boolean isTruncated(TextView text, String translation,
            int width, int height, int maxVisibleLines) {
        if (translation == null || translation.isEmpty()) return false;
        int contentWidth = width - text.getPaddingLeft() - text.getPaddingRight();
        int contentHeight = height - text.getPaddingTop() - text.getPaddingBottom();
        if (contentWidth <= 0 || contentHeight <= 0) return true;
        StaticLayout full = StaticLayout.Builder.obtain(translation, 0,
                        translation.length(), text.getPaint(), contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.09f)
                .build();
        return exceedsVisibleSpace(full.getHeight(), contentHeight,
                full.getLineCount(), maxVisibleLines);
    }

    /** Pure layout decision for unit tests, independent of Android TextView. */
    static boolean exceedsVisibleSpace(int fullHeight, int allowedHeight,
            int fullLines, int visibleLines) {
        return fullHeight > allowedHeight || fullLines > visibleLines;
    }
}
