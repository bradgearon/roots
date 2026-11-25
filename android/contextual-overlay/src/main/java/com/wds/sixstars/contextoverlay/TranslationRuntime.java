package com.wds.sixstars.contextoverlay;

import android.content.Context;

/** Injectable transport. The default implementation calls the existing desktop LAN mobile bridge. */
public final class TranslationRuntime {
    private static volatile TranslationProvider.Factory factory = LanPLaMoTranslationProvider::new;
    private TranslationRuntime() { }

    public static void useProvider(TranslationProvider.Factory providerFactory) {
        if (providerFactory == null) throw new IllegalArgumentException("Provider factory is required");
        factory = providerFactory;
    }

    public static String transportStatus() {
        return PersistentMobileTranslationSocket.instance().status();
    }

    public static void disconnect() {
        PersistentMobileTranslationSocket.instance().disconnect();
    }

    public static TranslationProvider provider(Context context) {
        return factory.create(context.getApplicationContext());
    }
}
