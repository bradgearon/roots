// Copyright (c) 2026 We Develop Software LLC. All rights reserved.
package com.wds.roots.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;

/** Explicit, user-initiated entry to the separately installed Roots app. */
public final class RootsBridge {
    private static final String ROOTS_PACKAGE = "com.wds.roots";

    private RootsBridge() {}

    // Call only in response to a user gesture. No background collection, IPC or inference.
    public static boolean openRoots(Activity activity) {
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(ROOTS_PACKAGE);
        if (intent == null) return false;
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
