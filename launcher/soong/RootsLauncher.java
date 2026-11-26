// Copyright (c) 2026 We Develop Software LLC. All rights reserved.
package com.wds.roots.launcher;

import com.android.launcher3.Launcher;

/**
 * The only HOME activity adapter. Launcher3 owns the entire workspace and lifecycle.
 * Future features delegate to Roots-owned collaborators, never patched upstream classes.
 * Intentionally has no overrides until a feature needs a documented extension point.
 */
public class RootsLauncher extends Launcher {
}
