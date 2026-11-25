package com.wds.sixstars.contextoverlay;

import android.content.Context;

/**
 * Portable transport boundary. The default implementation calls the existing
 * desktop mobile bridge over authenticated HTTP on trusted Wi-Fi. A future
 * standalone app can replace it without changing overlays or X scanning.
 * Implementations respond asynchronously and call back exactly once.
 */
public interface TranslationProvider {
    void translate(String text, String sourceLanguageHint, String targetLanguage, Result callback);

    interface Result {
        void completed(String translation, String error);
    }

    interface Factory {
        TranslationProvider create(Context context);
    }
}
