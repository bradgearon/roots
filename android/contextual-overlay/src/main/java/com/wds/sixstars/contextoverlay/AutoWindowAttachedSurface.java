package com.wds.sixstars.contextoverlay;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.graphics.Color;
import android.graphics.HardwareBufferRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.HardwareBufferRenderer.RenderResult;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceControl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * API 34+ selective X compositor. No SurfaceControlViewHost or full-screen
 * input channel: the attached BufferLayer has NO touch target, so the X
 * RecyclerView keeps owning gestures and the separate small accessibility
 * details buttons remain the only touchable Roots controls.
 *
 * RenderNode -> HardwareBufferRenderer (Android RenderThread) ->
 * SurfaceControl.Transaction -> X window's compositor subtree.
 *
 * Two separately owned GPU buffers avoid writing to a buffer the compositor
 * is still reading. A release fence must signal before its slot is reusable.
 * No polling, no private X APIs, and no claim to follow inner RecyclerView
 * scrolling without accessibility geometry.
 */
@TargetApi(34)
final class AutoWindowAttachedSurface {
    private static final int BUFFER_COUNT = 2;
    private static final long FRAME_STALL_MS=300L;
    private static final long USAGE = HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            | HardwareBuffer.USAGE_COMPOSER_OVERLAY;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService fenceWorker = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "SixStars-OverlayFences");
                thread.setDaemon(true);
                return thread;
            });
    private final AutoScenePresentation presentation;
    private final SurfaceControl surface;
    private final Slot[] slots = new Slot[BUFFER_COUNT];
    private final int width;
    private final int height;
    private Rect windowBounds;

    private List<AutoOverlaySceneView.TextItem> latest = Collections.emptyList();
    private long sceneEpoch;
    private boolean drawing;
    private boolean dirty;
    private boolean closed;
    private boolean failed;
    private final Runnable onFailure;
    private boolean watchdogArmed;
    private long submittedFrame,committedFrame;
    private boolean waitingForFrame(){return dirty||drawing||submittedFrame>committedFrame;}
    // A dirty frame may wait for a GPU render or a release fence. Do not
    // leave its predecessor frozen onscreen if neither callback arrives.
    private final Runnable frameWatchdog=()->{
        watchdogArmed=false;
        if(!closed&&!failed&&!latest.isEmpty()&&waitingForFrame())fail();
    };
    private void watchFrame(){
        if(!watchdogArmed&&!closed&&!failed&&!latest.isEmpty()&&waitingForFrame())
            watchdogArmed=main.postDelayed(frameWatchdog,FRAME_STALL_MS);
    }
    private void cancelWatchdog(){main.removeCallbacks(frameWatchdog);watchdogArmed=false;}

    AutoWindowAttachedSurface(AccessibilityService service,
            AutoWindowTarget target, AutoScenePresentation presentation,
            Runnable onFailure) {
        if (!target.isValid()) throw new IllegalArgumentException("Missing X window");
        this.presentation = presentation;
        this.onFailure = onFailure;
        this.windowBounds = new Rect(target.screenBounds);
        width = windowBounds.width();
        height = windowBounds.height();

        if (!HardwareBuffer.isSupported(width, height,
                HardwareBuffer.RGBA_8888, 1, USAGE))
            throw new IllegalStateException("No compatible transparent GPU buffers");

        SurfaceControl created = new SurfaceControl.Builder()
                .setName("Roots X selective compositor")
                .setBufferSize(width, height)
                .setFormat(PixelFormat.RGBA_8888)
                .setOpaque(false)
                .build();
        surface = created;
        try {
            for (int i = 0; i < BUFFER_COUNT; i++) slots[i] = new Slot(i);
            // SurfaceControl is a buffer layer WITHOUT a window/input channel.
            // Android places it in X's hierarchy; the controls stay elsewhere.
            service.attachAccessibilityOverlayToWindow(target.windowId, surface);
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setLayer(surface, 1000).setVisibility(surface, true).apply();
            }
        } catch (RuntimeException | LinkageError error) {
            close();
            throw error;
        }
    }

    void present(List<AutoOverlaySceneView.TextItem> source, AutoWindowTarget target) {
        if (closed || failed) return;
        windowBounds = new Rect(target.screenBounds);
        present(source);
    }

    void present(List<AutoOverlaySceneView.TextItem> source) {
        if (closed || failed) return;
        latest = Collections.unmodifiableList(new ArrayList<>(source));
        // Every new scene supersedes a render still being produced, but
        // retain the last committed buffer until its replacement is ready.
        ++sceneEpoch;
        if(latest.isEmpty()){
            // Hiding does not depend on acquiring a free hardware buffer.
            dirty=false;committedFrame=submittedFrame;cancelWatchdog();
            try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()){
                transaction.setVisibility(surface,false).setBuffer(surface,null).apply();
            }catch(RuntimeException e){fail();}
            return;
        }
        dirty = true;
        watchFrame();
        pump();
    }

    private void pump() {
        if (closed || failed || drawing || !dirty) return;
        Slot slot = null;
        for (Slot candidate : slots) {
            if (candidate != null && !candidate.busy && !candidate.disposed) {
                slot = candidate;
                break;
            }
        }
        if (slot == null) return;
        dirty = false;
        drawing = true;
        slot.busy = true;
        final Slot frameSlot = slot;
        final long frameEpoch=sceneEpoch;

        try {
            // Record on our main thread, execute GPU rendering on Android's
            // RenderThread, and return to main without blocking accessibility.
            RecordingCanvas canvas = slot.node.beginRecording(width, height);
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            presentation.draw(canvas, latest, windowBounds.left, windowBounds.top);
            slot.node.endRecording();

            slot.renderer.setContentRoot(slot.node);
            slot.renderer.obtainRenderRequest().draw(
                    command -> main.post(command),
                    result -> onRendered(frameSlot, result,frameEpoch));
        } catch (RuntimeException error) {
            drawing = false;
            slot.busy = false;
            // Future X changes will attempt another frame, but never spin.
            if (closed) {
                slot.dispose();
                shutdownWhenDone();
            } else {
                fail();
            }
        }
    }

    private void onRendered(Slot slot, RenderResult result,long frameEpoch) {
        drawing = false;
        android.hardware.SyncFence ready = result.getFence();
        if (closed || frameEpoch!=sceneEpoch) {
            // Never submit a frame for an invalidated geometry epoch. The
            // GPU may still be writing this never-presented buffer.
            // Await its render fence away from the main/UI thread before
            // disposing it, even if the X window was closed in the meantime.
            fenceWorker.execute(() -> {
                try (android.hardware.SyncFence fence = ready) {
                    if (fence.isValid()) fence.awaitForever();
                }
                main.post(() -> {
                    slot.busy = false;
                    if(closed){slot.dispose();shutdownWhenDone();}
                    else pump();
                });
            });
            // The other slot may already be free for the current geometry.
            if(!closed)pump();
            return;
        }
        if (result.getStatus() != RenderResult.SUCCESS) {
            ready.close();
            slot.busy = false;
            fail();
            return;
        }

        try {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setBuffer(surface, slot.buffer, ready, releaseFence ->
                        fenceWorker.execute(() -> {
                            // Fence waiting is ALWAYS off the UI thread.
                            if (releaseFence != null) {
                                try (android.hardware.SyncFence fence = releaseFence) {
                                    if (fence.isValid()) fence.awaitForever();
                                }
                            }
                            main.post(() -> {
                                slot.busy = false;
                                if (closed) {
                                    slot.dispose();
                                    shutdownWhenDone();
                                } else {
                                    pump();
                                }
                            });
                        }));
                // A render submitted before an uncertain-scroll hide must
                // never make old text visible again.
                transaction.setVisibility(surface,frameEpoch==sceneEpoch&&!latest.isEmpty());
                long sequence=++submittedFrame;
                transaction.addTransactionCommittedListener(command->main.post(command),()->{
                    if(closed||failed||sequence<=committedFrame)return;
                    committedFrame=sequence;
                    cancelWatchdog();watchFrame();
                });
                transaction.apply();
            }
        } catch (RuntimeException error) {
            slot.busy = false;
            if (closed) {
                slot.dispose();
                shutdownWhenDone();
            } else {
                fail();
            }
        } finally {
            ready.close();
        }

        // Only an actual SurfaceControl commit resets the watchdog. A
        // successful render callback alone does not prove that a new frame
        // was accepted by the compositor.
        watchFrame();
        // Submit the newest replacement while the previous buffer is
        // displayed; the old slot is only reused once its release fence fires.
        pump();
    }

    void close() {
        if (closed) return;
        closed = true;
        dirty = false;
        cancelWatchdog();
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setVisibility(surface,false);
            transaction.setBuffer(surface, null);
            transaction.reparent(surface, null);
            transaction.apply();
        } catch (RuntimeException ignored) { }
        surface.release();
        for (Slot slot : slots) {
            if (slot != null && !slot.busy) slot.dispose();
        }
        shutdownWhenDone();
    }

    /** Fence callbacks can finish after the surface has been detached. */
    private void shutdownWhenDone() {
        if (!closed) return;
        for (Slot slot : slots)
            if (slot != null && !slot.disposed) return;
        fenceWorker.shutdown();
    }

    private void fail() {
        if (failed || closed) return;
        failed = true;
        close();
        // Android controls remain responsive; the manager restores the
        // trusted, FLAG_NOT_TOUCHABLE WindowManager fallback on main.
        main.post(onFailure);
    }

    private final class Slot {
        final HardwareBuffer buffer;
        final HardwareBufferRenderer renderer;
        final RenderNode node;
        boolean busy;
        boolean disposed;

        Slot(int index) {
            buffer = HardwareBuffer.create(width, height,
                    HardwareBuffer.RGBA_8888, 1, USAGE);
            renderer = new HardwareBufferRenderer(buffer);
            node = new RenderNode("SixStars-X-" + index);
            node.setPosition(0, 0, width, height);
        }

        void dispose() {
            if (disposed) return;
            disposed = true;
            renderer.close();
            buffer.close();
        }
    }
}
