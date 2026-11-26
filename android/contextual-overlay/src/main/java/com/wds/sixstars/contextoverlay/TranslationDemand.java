package com.wds.sixstars.contextoverlay;

/**
 * Main-thread demand predictor. Coordinates here are ONLY for selecting work.
 * The renderer never receives predicted positions.
 */
final class TranslationDemand {
    static final long MOTION_IDLE_MS = 450;
    private int height = 1;
    private long lastEvent = -1, lastMotion = -1;
    private double pixelsPerMs, measuredOffset;
    private boolean trusted;

    void snapshot(int viewportHeight) {
        height = Math.max(1, viewportHeight);
        measuredOffset = 0;
        trusted = true;
    }

    void measuredScroll(int dy,long now) {
        // A missing earlier displacement cannot be recovered by a later delta.
        if (trusted) measuredOffset += dy;
        long dt = lastEvent < 0 ? 80 : Math.max(16, Math.min(180, now-lastEvent));
        boolean fresh = lastMotion < 0 || now-lastMotion >= MOTION_IDLE_MS;
        lastEvent = now;
        if (dy == 0) return;
        double sample = Math.max(-12.0, Math.min(12.0, dy/(double)dt));
        // Reverse immediately; don't let an old direction bias the new gesture.
        pixelsPerMs = fresh || Math.signum(sample)!=Math.signum(pixelsPerMs)
                ? sample : 0.55*sample + 0.45*pixelsPerMs;
        lastMotion = now;
    }

    void invalidate() {
        trusted=false; measuredOffset=0; pixelsPerMs=0;
        lastEvent=lastMotion=-1;
    }

    boolean trusted() { return trusted; }
    int height() { return height; }
    double offset() { return measuredOffset; }
    double velocity(long now) {
        if (lastMotion < 0) return 0;
        long elapsed=Math.max(0,now-lastMotion);
        if(elapsed>=MOTION_IDLE_MS)return 0;
        // A short pause gradually loses directional confidence.
        return pixelsPerMs * Math.min(1.0,(MOTION_IDLE_MS-elapsed)/300.0);
    }
    long idleDelay(long now) {
        return lastMotion<0 ? 0 : Math.max(0,MOTION_IDLE_MS-(now-lastMotion));
    }
}
