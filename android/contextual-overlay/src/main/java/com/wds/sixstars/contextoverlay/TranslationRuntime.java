package com.wds.sixstars.contextoverlay;

import android.content.Context;

/** Injectable transport. The default implementation calls the existing desktop LAN mobile bridge. */
public final class TranslationRuntime {
    private static volatile boolean lastDesktop;
    private static volatile TranslationProvider.Factory factory = context -> {
        OverlayPreferences prefs = new OverlayPreferences(context);
        return (text, source, target, callback) -> {
            lastDesktop = prefs.desktopTranslationEnabled();
            TranslationProvider provider = lastDesktop ? new LanPLaMoTranslationProvider(context) : new LocalPLaMoTranslationProvider(context);
            provider.translate(text, source, target, callback);
        };
    };
    private TranslationRuntime() { }

    public static void useProvider(TranslationProvider.Factory providerFactory) {
        if (providerFactory == null) throw new IllegalArgumentException("Provider factory is required");
        factory = providerFactory;
    }

    public static String transportStatus() {
        return lastDesktop ? PersistentMobileTranslationSocket.instance().status() : "On-device PLaMo";
    }

    public static void disconnect() {
        PersistentMobileTranslationSocket.instance().disconnect();
    }

    public static TranslationProvider provider(Context context) {
        return factory.create(context.getApplicationContext());
    }
}
