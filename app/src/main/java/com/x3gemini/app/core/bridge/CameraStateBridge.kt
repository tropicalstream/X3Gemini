package com.x3gemini.app.core.bridge

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 2 Step 2c.4 — cross-Activity bridge for the unipanel camera
 * status chip.
 *
 * visionclaw's MainActivity already observes
 * [viewModel.voiceAssistantActive] to drive the on-screen camera
 * column. We mirror that boolean into this process-local singleton
 * so the tapbrowser overlay can render a small "Cam" indicator in
 * the top-right corner when Gemini Live is actively watching.
 *
 * Same pattern as [ChatCardBridge] and
 * [com.TapLink.app.media.BrowserFrameHolder] — Binder-free,
 * thread-safe state, listener API.
 *
 * Why state-only (no live frames): a full mirrored TextureView
 * would require extending the Camera2 session to write to two
 * SurfaceTextures, which is invasive. The chip gives the user
 * the "yes Gemini can see me right now" feedback without that
 * complexity. Step 2c.4 v2 can upgrade to a live preview later
 * if needed.
 */
object CameraStateBridge {

    private val state = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /**
     * Set the camera-active state. Triggers every registered
     * listener synchronously on the calling thread when the value
     * actually changes; duplicate publishes are dropped to keep
     * listeners quiet.
     */
    fun publish(active: Boolean) {
        val previous = state.getAndSet(active)
        if (previous == active) return
        for (l in listeners) {
            try {
                l(active)
            } catch (_: Throwable) {
                // Swallow — a misbehaving listener must not break
                // the publisher or other listeners.
            }
        }
    }

    /** Current snapshot of the published state. */
    fun current(): Boolean = state.get()

    /**
     * Subscribe to publishes. The supplied [listener] fires once
     * synchronously with the current state, then on every future
     * value change. Returns an [AutoCloseable] for unsubscribe.
     */
    fun observe(listener: (Boolean) -> Unit): AutoCloseable {
        listeners.add(listener)
        try {
            listener(state.get())
        } catch (_: Throwable) {
            // ditto.
        }
        return AutoCloseable { listeners.remove(listener) }
    }
}
