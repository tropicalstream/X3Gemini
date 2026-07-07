package com.x3gemini.app.core.bridge

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local bridge for the voice / Gemini-Live HUD state. Ported
 * from TapInsight unipanel, pruned to what X3Gemini renders: voice
 * phase, live transcript, oscilloscope level/channel, connection
 * status, and a one-line transient notification.
 *
 * Threading mirrors [ChatCardBridge] / [CameraStateBridge]: state
 * writes are atomic, listeners fire on the publisher's thread, UI
 * consumers must hop to main themselves.
 */
object HudStateBridge {

    enum class VoicePhase { IDLE, LISTENING, THINKING, FOLLOW_UP }

    /** Color hint for the orb glow: red = user speaking, green/blue = model. */
    enum class OscilloscopeChannel { USER, MODEL }

    enum class ConnectionStatus {
        IDLE,
        CONNECTING,
        GEMINI_CONNECTED,
        DEGRADED,
        ERROR
    }

    /**
     * Snapshot of everything the overlay needs. Immutable; mutate by
     * publishing a new [State].
     *
     * [notification] is a one-line transient HUD message, or null.
     * Consumers fade it out themselves; the bridge does NOT time it out.
     */
    data class State(
        val phase: VoicePhase = VoicePhase.IDLE,
        val transcript: String? = null,
        val oscilloscopeLevel: Float = 0f,
        val oscilloscopeChannel: OscilloscopeChannel = OscilloscopeChannel.USER,
        val connection: ConnectionStatus = ConnectionStatus.IDLE,
        val notification: String? = null
    )

    private val state = AtomicReference(State())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    /** Replace the published state; triggers every listener synchronously. */
    fun publish(next: State) {
        state.set(next)
        for (l in listeners) {
            try {
                l(next)
            } catch (_: Throwable) {
                // Misbehaving listeners must never break the publisher.
            }
        }
    }

    /** Apply a transform to the current state and publish the result. */
    inline fun update(transform: (State) -> State) {
        publish(transform(current()))
    }

    /** Most recently published snapshot. */
    fun current(): State = state.get()

    /**
     * Subscribe. Fires once synchronously with the current state, then
     * on every future [publish]. Returns an [AutoCloseable].
     */
    fun observe(listener: (State) -> Unit): AutoCloseable {
        listeners.add(listener)
        try {
            listener(state.get())
        } catch (_: Throwable) {
            // ditto.
        }
        return AutoCloseable { listeners.remove(listener) }
    }
}
