package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;

import java.util.Date;

/**
 * Non-sensitive on-device diagnostics for the optional X accessibility reader.
 * Stores only aggregate counts/status. Never persists tweet text, identifiers,
 * translation requests, or screen coordinates.
 */
public final class OverlayDiagnostics {
    private static final String FILE = "contextual_overlay_diagnostics";
    private static final String KEY_TIME = "last_scan_time";
    private static final String KEY_PHASE = "last_scan_phase";
    private static final String KEY_X_TIME = "last_x_scan_time";
    private static final String KEY_NODES = "visited_node_count";
    private static final String KEY_TEXT = "text_node_count";
    private static final String KEY_MATCHES = "candidate_count";
    private static final String KEY_BADGES = "visible_badge_count";
    private static final String KEY_ERROR = "overlay_error";
    private static final String KEY_AUTO_MODE = "auto_mode";
    private static final String KEY_AUTO_ELIGIBLE = "auto_eligible_count";
    private static final String KEY_AUTO_PLACEABLE = "auto_placeable_count";
    private static final String KEY_AUTO_RENDERED = "auto_rendered_count";
    private static final String KEY_AVATAR_NODES = "last_avatar_nodes";
    private static final String KEY_AUTHOR_SAMPLES = "last_author_samples";
    private static final String KEY_AUTHOR_MATCHES = "last_author_matches";
    private static final String KEY_CLIPPED_NAMES = "last_clipped_names";

    private OverlayDiagnostics() { }

    public static void record(Context context, String phase, int nodes, int text, int candidates,
                              int badges, String error) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit()
                .putLong(KEY_TIME, System.currentTimeMillis())
                .putString(KEY_PHASE, phase);
        // Preserve the latest X scan when the user opens Roots settings:
        // leaving X necessarily generates an "Outside X" accessibility event.
        if (phase != null && phase.startsWith("X active;")) {
            editor.putLong(KEY_X_TIME, System.currentTimeMillis())
                    .putInt(KEY_NODES, Math.max(0, nodes))
                    .putInt(KEY_TEXT, Math.max(0, text))
                    .putInt(KEY_MATCHES, Math.max(0, candidates))
                    .putInt(KEY_BADGES, Math.max(0, badges))
                    .putString(KEY_ERROR, error == null ? "" : error);
        }
        editor.apply();
    }

    /**
     * Only aggregate stage counters for the most recent foreground X scan.
     * No post text, content hashes, URLs, token values or coordinates are stored.
     */
    public static void recordAuto(Context context, boolean autoMode, int eligible,
                                  int placeable, int rendered) {
        context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_MODE, autoMode)
                .putInt(KEY_AUTO_ELIGIBLE, Math.max(0, eligible))
                .putInt(KEY_AUTO_PLACEABLE, Math.max(0, placeable))
                .putInt(KEY_AUTO_RENDERED, Math.max(0, rendered))
                .apply();
    }

    /** Stage counts only; never store account names or tweet content. */
    public static void recordAuthorCounts(Context context,
            int avatars, int samples, int matches, int clipped) {
        context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_AVATAR_NODES, Math.max(0, avatars))
                .putInt(KEY_AUTHOR_SAMPLES, Math.max(0, samples))
                .putInt(KEY_AUTHOR_MATCHES, Math.max(0, matches))
                .putInt(KEY_CLIPPED_NAMES, Math.max(0, clipped))
                .apply();
    }

    public static String summary(Context context) {
        SharedPreferences p = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        long time = p.getLong(KEY_TIME, 0L);
        if (time == 0L) return "No accessibility scans recorded yet.";
        String when = DateFormat.format("HH:mm:ss", new Date(time)).toString();
        String message = "Last service event " + when + ": " + p.getString(KEY_PHASE, "unknown");
        long xTime = p.getLong(KEY_X_TIME, 0L);
        if (xTime == 0L) return message + "\nNo X scan recorded yet.";
        message += "\nLast X scan " + DateFormat.format("HH:mm:ss", new Date(xTime))
                + ": nodes " + p.getInt(KEY_NODES, 0)
                + " · text " + p.getInt(KEY_TEXT, 0)
                + " · candidates " + p.getInt(KEY_MATCHES, 0)
                + " · badges " + p.getInt(KEY_BADGES, 0);
        if (p.getBoolean(KEY_AUTO_MODE, false)) {
            message += "\nAuto inline: eligible " + p.getInt(KEY_AUTO_ELIGIBLE, 0)
                    + " · placeable " + p.getInt(KEY_AUTO_PLACEABLE, 0)
                    + " · shown " + p.getInt(KEY_AUTO_RENDERED, 0);
            message += "\nNames: avatars " + p.getInt(KEY_AVATAR_NODES, 0)
                    + " · possible names " + p.getInt(KEY_AUTHOR_SAMPLES, 0)
                    + " · matched " + p.getInt(KEY_AUTHOR_MATCHES, 0)
                    + " · clipped " + p.getInt(KEY_CLIPPED_NAMES, 0);
        } else {
            message += "\nDisplay mode at last X scan: Manual";
        }
        String error = p.getString(KEY_ERROR, "");
        return error == null || error.isEmpty() ? message : message + "\nOverlay error: " + error;
    }
}
