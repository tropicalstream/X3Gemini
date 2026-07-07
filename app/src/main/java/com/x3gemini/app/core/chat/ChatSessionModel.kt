package com.x3gemini.app.core.chat

import android.util.Log
import com.x3gemini.app.core.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Slim replacement for TapInsight's MainViewModel chat state. Owns the
 * in-memory chat card list and the Gemini Live streaming-turn buffer.
 * The streaming merge heuristics are ported verbatim from MainViewModel
 * (they encode hard-won fixes for Gemini Live's whole-word fragment
 * deltas and cumulative resends).
 *
 * Persistence policy per Mars's spec: NO chat history is saved. The
 * message list and the previous-session context both live in RAM only —
 * enough for follow-up questions within and across sessions while the
 * app is running, gone on process death.
 */
class ChatSessionModel {

    companion object {
        private const val TAG = "ChatSessionModel"
        private const val MAX_ASSISTANT_CHAT_CARDS = 20
        private const val MAX_PREVIOUS_CONTEXT_CHARS = 12_000
        /** Previous-session context older than this is not injected. */
        private const val PREVIOUS_CHAT_MAX_AGE_MS = 6L * 60L * 60L * 1000L
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var liveAssistantWorkingTurn: String = ""
    private var liveAssistantWorkingCardIndex = -1

    // ── In-memory previous-conversation context (follow-ups only) ────
    @Volatile private var previousChatSummary: String? = null
    @Volatile private var previousChatSavedAtMs: Long = 0L

    // ── Streaming turn buffer ─────────────────────────────────────────

    @Synchronized
    fun appendLiveAssistantStreamChunk(text: String) {
        val safe = sanitizeAssistantDisplayText(text)
        if (safe.isBlank()) return
        appendLiveAssistantWorkingChunk(safe)
    }

    @Synchronized
    fun appendDirectAssistantResponse(text: String) {
        val safe = sanitizeAssistantDisplayText(text)
        if (safe.isBlank()) return
        appendAssistantInteraction(safe)
    }

    @Synchronized
    fun commitLiveAssistantStreamIfNeeded() {
        val live = liveAssistantWorkingTurn.trim()
        val hasWorkingCard = findLiveAssistantWorkingIndex(_messages.value) >= 0
        if (live.isBlank() && !hasWorkingCard) return
        if (live.isNotBlank() && !hasWorkingCard) {
            appendAssistantInteraction(live)
            return
        }
        clearLiveAssistantWorkingCard(commitIfPopulated = true)
    }

    @Synchronized
    fun resetLiveAssistantStream() {
        commitLiveAssistantStreamIfNeeded()
        liveAssistantWorkingTurn = ""
    }

    /** In-progress assistant turn (empty when idle). */
    fun snapshotLiveAssistantTurn(): String = liveAssistantWorkingTurn

    @Synchronized
    fun appendUserUtterance(text: String) {
        val entry = text.trim()
        if (entry.isBlank()) return
        val snapshot = _messages.value
        val last = snapshot.lastOrNull()
        if (last != null && last.fromUser && last.text == entry) return
        val updated = snapshot.toMutableList().apply {
            add(ChatMessage(text = entry, fromUser = true))
        }
        _messages.value = updated.takeLast(MAX_ASSISTANT_CHAT_CARDS)
    }

    fun getAssistantCardsSnapshot(): List<ChatMessage> =
        _messages.value.filterNot { it.fromUser }.takeLast(MAX_ASSISTANT_CHAT_CARDS)

    // ── Previous-session context (RAM only) ──────────────────────────

    /**
     * Snapshot the current assistant cards as follow-up context for the
     * NEXT session. Assistant cards ONLY — never the user's raw spoken
     * commands (TapInsight's cross-session contamination lesson).
     */
    fun saveChatContextForNextSession() {
        val cards = getAssistantCardsSnapshot()
        if (cards.isEmpty()) return
        previousChatSummary = cards.takeLast(10).joinToString("\n---\n") { card ->
            card.text.take(4000)
        }.take(MAX_PREVIOUS_CONTEXT_CHARS)
        previousChatSavedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Saved in-memory previous chat context: ${previousChatSummary?.length} chars")
    }

    fun getPreviousChatContext(): String? {
        val stored = previousChatSummary?.takeIf { it.isNotBlank() } ?: return null
        val ageMs = System.currentTimeMillis() - previousChatSavedAtMs
        if (previousChatSavedAtMs <= 0L || ageMs > PREVIOUS_CHAT_MAX_AGE_MS) return null
        return stored
    }

    fun getPreviousChatContextAgeMs(): Long {
        if (previousChatSavedAtMs <= 0L) return -1L
        return System.currentTimeMillis() - previousChatSavedAtMs
    }

    /** Clear cards + context (right-arm double-tap full exit keeps cards;
     *  this is only for an explicit fresh start if ever wired). */
    @Synchronized
    fun clearAll() {
        _messages.value = emptyList()
        liveAssistantWorkingTurn = ""
        liveAssistantWorkingCardIndex = -1
        previousChatSummary = null
        previousChatSavedAtMs = 0L
    }

    // ── Internals (ported verbatim from MainViewModel) ────────────────

    private fun appendLiveAssistantWorkingChunk(chunk: String) {
        // Gemini Live's output-transcription deltas arrive as whole-word
        // fragments WITHOUT boundary spaces — join with a space. Keep the
        // cumulative / stale / contains guards; deliberately NO
        // suffix/prefix overlap heuristic (it false-matched and dropped
        // words: "I am" + "amazing" → "I amazing").
        val next = chunk.trim()
        if (next.isBlank()) return
        val prev = liveAssistantWorkingTurn
        liveAssistantWorkingTurn = when {
            prev.isBlank() -> next
            next.startsWith(prev) -> next      // cumulative resend of the full turn
            prev.startsWith(next) -> prev      // stale / duplicate delta
            next.contains(prev) -> next        // cumulative (with leading context)
            prev.contains(next) -> prev        // already included
            else -> "$prev $next"              // distinct fragment — join with one space
        }
        upsertLiveAssistantWorkingCard(liveAssistantWorkingTurn)
    }

    private fun upsertLiveAssistantWorkingCard(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val current = _messages.value.toMutableList()
        val index = findLiveAssistantWorkingIndex(current)
        if (index >= 0) {
            current[index] = current[index].copy(text = clean, fromUser = false)
        } else {
            if (current.size >= MAX_ASSISTANT_CHAT_CARDS + 1) {
                current.removeAt(0)
            }
            current += ChatMessage(text = clean, fromUser = false)
            liveAssistantWorkingCardIndex = current.lastIndex
        }
        _messages.value = current
        liveAssistantWorkingCardIndex = current.indexOfLast { !it.fromUser && it.text == clean }
    }

    private fun appendAssistantInteraction(interactionText: String) {
        val entry = interactionText.trim()
        if (entry.isBlank()) return
        val current = _messages.value.toMutableList()
        val workingIndex = findLiveAssistantWorkingIndex(current)
        if (workingIndex >= 0) {
            current[workingIndex] = current[workingIndex].copy(text = entry, fromUser = false)
        } else {
            current += ChatMessage(text = entry, fromUser = false)
        }
        _messages.value = current.takeLast(MAX_ASSISTANT_CHAT_CARDS)
        liveAssistantWorkingTurn = ""
        liveAssistantWorkingCardIndex = -1
    }

    private fun findLiveAssistantWorkingIndex(messages: List<ChatMessage>): Int {
        val index = liveAssistantWorkingCardIndex
        return if (index in messages.indices && !messages[index].fromUser) index else -1
    }

    private fun clearLiveAssistantWorkingCard(commitIfPopulated: Boolean = false) {
        val current = _messages.value.toMutableList()
        val index = findLiveAssistantWorkingIndex(current)
        if (index >= 0) {
            val candidate = current[index].text.trim()
            if (commitIfPopulated && candidate.isNotBlank()) {
                _messages.value = current.takeLast(MAX_ASSISTANT_CHAT_CARDS)
                liveAssistantWorkingCardIndex = -1
                liveAssistantWorkingTurn = ""
                return
            }
            current.removeAt(index)
            _messages.value = current
        }
        liveAssistantWorkingCardIndex = -1
    }

    private fun sanitizeAssistantDisplayText(text: String): String {
        return text
            .lineSequence()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("thought:", ignoreCase = true) ||
                    t.startsWith("reasoning:", ignoreCase = true) ||
                    t.startsWith("<thinking>", ignoreCase = true) ||
                    t.startsWith("</thinking>", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
    }
}
