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

    String LANGUAGE_UNCERTAIN="Language could not be identified confidently.";

    default void translateChecked(String text,String source,String target,boolean name,boolean automatic,Result callback){
        translate(text,source,target,callback);
    }

    interface Result {
        default void identifiedLanguage(String language){}
        /** Local inference begins, or remote dispatch begins (not remote GPU execution). */
        default void started() {}
        /** Cumulative provisional text; never cache as final. */
        default void partial(String prefix) {}
        /** The original is already in the requested language; no generation occurred. */
        default void alreadyLocalized(String original,String language){completed(original,null);}
        void completed(String translation, String error);
    }

    interface Factory {
        TranslationProvider create(Context context);
    }
}
