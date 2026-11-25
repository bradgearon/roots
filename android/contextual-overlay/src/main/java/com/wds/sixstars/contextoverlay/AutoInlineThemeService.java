package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Paints an opaque surface over X's EXISTING post-text pixels. Android's system
 * theme may differ from X's own in-app theme; users can override the palette.
 * The text window remains exactly inside the post-body rectangle.
 */
public final class AutoInlineThemeService {
    public static final String SYSTEM = "system";
    public static final String BLACK = "black";
    public static final String DIM = "dim";
    public static final String LIGHT = "light";

    private AutoInlineThemeService() { }

    public static Palette resolve(Context context, String selected) {
        String mode = selected == null ? SYSTEM : selected;
        if (SYSTEM.equals(mode)) {
            boolean night = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES;
            mode = night ? BLACK : LIGHT;
        }

        switch (mode) {
            case LIGHT:
                return new Palette(0xFFFFFFFF, 0xFF0F1419, 0xFF536471);
            case DIM:
                return new Palette(0xFF15202B, 0xFFE7E9EA, 0xFF8B98A5);
            case BLACK:
            default:
                return new Palette(0xFF000000, 0xFFE7E9EA, 0xFF8B98A5);
        }
    }

    public static final class Palette {
        public final int background;
        public final int foreground;
        public final int controlForeground;

        Palette(int background, int foreground, int controlForeground) {
            this.background = background;
            this.foreground = foreground;
            this.controlForeground = controlForeground;
        }
    }
}
