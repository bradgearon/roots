package com.wds.sixstars.contextoverlay;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;

/**
 * Demand-driven, display-vsync scene scheduler. Never polls accessibility,
 * calls a model, or keeps a permanent 60/120Hz background loop running.
 * Called from either the accessibility stream or model-result callbacks.
 *
 * All Android View and WindowManager changes happen on the UI Looper.
 */
final class AutoOverlayFrameScheduler implements Choreographer.FrameCallback {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Choreographer choreographer;
    private final Runnable draw;
    private boolean pending;
    private boolean stopped;

    AutoOverlayFrameScheduler(Runnable draw) {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Create frame scheduler on UI Looper");
        choreographer = Choreographer.getInstance();
        this.draw = draw;
    }

    void request() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::request);
            return;
        }
        if (stopped || pending) return;
        pending = true;
        choreographer.postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        pending = false;
        if (!stopped) draw.run();
        // Intentionally no recursive postFrameCallback: stop when scene stable.
    }

    void stop() {
        stopped = true;
        if (Looper.myLooper() == Looper.getMainLooper())
            choreographer.removeFrameCallback(this);
        else main.post(() -> choreographer.removeFrameCallback(this));
        pending = false;
    }
}
