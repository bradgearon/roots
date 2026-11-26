package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.content.SharedPreferences;

/** This module keeps its own settings; no dependency on the Roots Activity. */
public final class OverlayPreferences {
    private static final String FILE = "contextual_overlay";
    private static final String CONSENT = "consented_to_x_visible_text";
    private static final String TARGET = "target_language";
    private static final String AUTO_PREVIEW = "auto_preview_foreign_x";
    private static final String INLINE_THEME = "auto_inline_theme";
    private static final String DESKTOP_TRANSLATION = "desktop_translation_consent";
    private static final String BRIDGE_URL = "translation_bridge_url";
    
    private final SharedPreferences prefs;
    private final Context prefsContext;

    public OverlayPreferences(Context context) {
        prefsContext = context.getApplicationContext();
        prefs = prefsContext.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean enabled() { return prefs.getBoolean(CONSENT, false); }
    public void setEnabled(boolean value) { prefs.edit().putBoolean(CONSENT, value).apply(); }
    public boolean translationEnabled() { return enabled(); }
    public boolean desktopTranslationEnabled() {
        return prefs.getBoolean(DESKTOP_TRANSLATION, false);
    }
    public void setDesktopTranslationEnabled(boolean value) {
        prefs.edit().putBoolean(DESKTOP_TRANSLATION, value).apply();
    }
    public String bridgeUrl() {
        String saved = prefs.getString(BRIDGE_URL, "");
        if (saved == null || saved.trim().isEmpty()) {
            // Integration-only preference adapter; the module remains independent
            // of Roots Java classes and can migrate into a separate app.
            saved = "https://127.0.0.1:8223";
        } else if(!prefs.getBoolean("bridge_port_8223_migrated",false)){
            // Migrate only the former standard Roots bridge port.
            saved=saved.replaceFirst(":8123(?=/|$)",":8223");
            prefs.edit().putString(BRIDGE_URL,saved).putBoolean("bridge_port_8223_migrated",true).apply();
        }
        return saved == null ? "" : saved.trim().replaceAll("/+$", "").replaceFirst("^http://","https://");
    }
    public void setBridgeUrl(String url) {
        String value = url == null ? "" : url.trim().replaceAll("/+$", "");
        value=value.replaceFirst("^http://","https://");
        if (!value.startsWith("https://"))
            throw new IllegalArgumentException("Bridge URL must begin with https://");
        try {
            java.net.URL parsed = new java.net.URL(value);
            if (parsed.getHost().isEmpty() || !parsed.getPath().isEmpty() && !"/".equals(parsed.getPath())
                    || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getRef() != null)
                throw new IllegalArgumentException("Enter a bridge base URL, for example https://192.168.1.100:8223");
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Invalid bridge URL");
        }
        prefs.edit().putString(BRIDGE_URL, value).putBoolean("bridge_port_8223_migrated",true).apply();
    }
    public String inlineTheme() {
        String value = prefs.getString(INLINE_THEME, AutoInlineThemeService.SYSTEM);
        return value == null ? AutoInlineThemeService.SYSTEM : value;
    }
    public void setInlineTheme(String value) {
        if (!AutoInlineThemeService.SYSTEM.equals(value)
                && !AutoInlineThemeService.BLACK.equals(value)
                && !AutoInlineThemeService.DIM.equals(value)
                && !AutoInlineThemeService.LIGHT.equals(value)) {
            throw new IllegalArgumentException("Unsupported inline theme");
        }
        prefs.edit().putString(INLINE_THEME, value).apply();
    }
    /** Experimental GPU mode is opt-in; normal builds use accelerated CPU kernels. */
    public boolean localVulkanEnabled() { return prefs.getBoolean("local_vulkan", false); }
    public void setLocalVulkanEnabled(boolean value) { prefs.edit().putBoolean("local_vulkan", value).apply(); }
    public int localThreadSetting() {
        int n=prefs.getInt("local_threads",0);
        return n==2||n==4||n==6||n==8?n:0;
    }
    public void setLocalThreadSetting(int n) {
        if(n!=0&&n!=2&&n!=4&&n!=6&&n!=8)throw new IllegalArgumentException("Unsupported thread count");
        prefs.edit().putInt("local_threads",n).apply();
    }
    public int localThreads() {
        int requested=localThreadSetting();
        // Six is a starting point for Tensor G5's six performance/mid cores,
        // not an affinity promise or a universally optimal thread count.
        return Math.max(1,Math.min(Runtime.getRuntime().availableProcessors(),requested==0?6:requested));
    }
    /** Outstanding overlay requests, not simultaneous native contexts. */
    public int translationRequestLimit() {
        int n=prefs.getInt("translation_request_limit",1);
        return n>=1&&n<=4?n:1;
    }
    public void setTranslationRequestLimit(int n) {
        if(n<1||n>4)throw new IllegalArgumentException("Unsupported request limit");
        prefs.edit().putInt("translation_request_limit",n).apply();
    }
    public boolean showTranslationActivity() { return prefs.getBoolean("show_translation_activity", true); }
    public void setShowTranslationActivity(boolean value) { prefs.edit().putBoolean("show_translation_activity",value).apply(); }
    public boolean autoPreview() { return prefs.getBoolean(AUTO_PREVIEW, false); }
    public void setAutoPreview(boolean value) { prefs.edit().putBoolean(AUTO_PREVIEW, value).apply(); }
    public String targetLanguage() { return prefs.getString(TARGET, "en"); }
    public void setTargetLanguage(String language) {
        if (!"en".equals(language) && !"es".equals(language) && !"fr".equals(language)
                && !"de".equals(language) && !"ja".equals(language)) {
            throw new IllegalArgumentException("Unsupported target language");
        }
        prefs.edit().putString(TARGET, language).apply();
    }
}
