package com.x3gemini.app.core.bridge

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 2 Step 2c.3 — cross-Activity bridge for the unipanel
 * mini-card stack.
 *
 * The chat conversation state lives in visionclaw's MainViewModel
 * (app module). The tapbrowser overlay (this module) needs to
 * render the last few exchanges as compact mini cards over the
 * WebView. The two Activities run in the same process but are in
 * different modules and different Activities, so a process-local
 * singleton is the lightest-weight way to ferry the state across:
 * no Binder, no Intent, no Service.
 *
 * The pattern matches [com.TapLink.app.media.BrowserFrameHolder],
 * which Step 1 used to bridge the WebView reference into Gemini's
 * vision tool. Same idea: thread-safe holder, weak listeners,
 * pushers don't need to know what (if anything) is observing.
 *
 * Threading: state writes are atomic. Listeners fire on the
 * publisher's thread — UI consumers must hop to main themselves
 * (the tapbrowser MainActivity uses uiHandler.post).
 *
 * The bridge does NOT cap the list size. visionclaw already trims
 * its chat history to MAX_ASSISTANT_CHAT_CARDS before publishing,
 * and the consumer renders only the N most-recent entries.
 */
object ChatCardBridge {

    /**
     * Minimal representation of a single chat row, decoupled from
     * visionclaw's [com.rayneo.visionclaw.core.model.ChatMessage]
     * so the tapbrowser module doesn't need to depend on the app
     * module's package layout.
     */
    data class Card(
        val text: String,
        val fromUser: Boolean,
        val timestampMs: Long
    )

    private val state = AtomicReference<List<Card>>(emptyList())
    private val listeners = CopyOnWriteArrayList<(List<Card>) -> Unit>()

    /**
     * Replace the published card list with [cards] (most-recent
     * last, matching the visionclaw chat order). Triggers every
     * registered listener synchronously on the calling thread.
     */
    fun publish(cards: List<Card>) {
        state.set(cards)
        for (l in listeners) {
            try {
                l(cards)
            } catch (_: Throwable) {
                // Swallow — a misbehaving listener must not break
                // the publisher or other listeners.
            }
        }
    }

    /** Snapshot of the most recently published list. */
    fun current(): List<Card> = state.get()

    /**
     * Subscribe to publishes. The supplied [listener] fires once
     * synchronously with the current state, then on every future
     * [publish]. Returns an [AutoCloseable] the caller can use to
     * unsubscribe (typically tied to an Activity's lifecycle).
     */
    fun observe(listener: (List<Card>) -> Unit): AutoCloseable {
        listeners.add(listener)
        try {
            listener(state.get())
        } catch (_: Throwable) {
            // ditto — never let a buggy listener escape.
        }
        return AutoCloseable { listeners.remove(listener) }
    }
}
