package com.x3gemini.app.core.tools

import android.content.Context
import com.x3gemini.app.core.config.AssistantStore
import java.util.Locale

/**
 * assistant_memory — persistent memory + custom instructions, the two
 * halves of "a real assistant, not a stripped-down Q&A tool":
 *
 *   remember / forget / list / clear — durable user facts, injected
 *   into every future session's system prompt.
 *   set_instructions / show_instructions / clear_instructions —
 *   persistent personalization ("always answer like a pirate", "call
 *   me Mars", "keep answers under two sentences").
 *
 * Changes to instructions apply fully from the NEXT session (the
 * system prompt is fixed per Live connection), so the tool result says
 * so and the model is told to also follow them immediately in-session.
 */
class MemoryTool(private val context: Context) : AiTapTool {

    override val name = "assistant_memory"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        AssistantStore.init(context)
        return when (val action = (args["action"] ?: "").trim().lowercase(Locale.US)) {
            "remember" -> {
                val text = (args["text"] ?: "").trim()
                if (text.isBlank()) {
                    Result.failure(IllegalArgumentException("remember needs 'text' — the fact to keep."))
                } else if (AssistantStore.remember(text)) {
                    Result.success("Remembered: \"$text\". It will be available in every future conversation.")
                } else {
                    Result.success("Couldn't store that memory (empty after trimming).")
                }
            }
            "forget" -> {
                val query = (args["text"] ?: "").trim()
                val removed = AssistantStore.forget(query)
                if (removed != null) Result.success("Forgot: \"$removed\".")
                else Result.success("No stored memory matches \"$query\". ${memorySummary()}")
            }
            "list" -> Result.success(memorySummary())
            "clear" -> {
                AssistantStore.clearMemories()
                Result.success("Cleared all memories.")
            }
            "set_instructions" -> {
                val text = (args["text"] ?: "").trim()
                if (text.isBlank()) {
                    Result.failure(IllegalArgumentException("set_instructions needs 'text'."))
                } else {
                    AssistantStore.setInstructions(text)
                    Result.success(
                        "Custom instructions saved (${text.length} chars). Follow them from " +
                            "now on in this conversation too; they auto-apply to all future sessions."
                    )
                }
            }
            "show_instructions" -> Result.success(
                AssistantStore.instructions()?.let { "Current custom instructions: $it" }
                    ?: "No custom instructions are set."
            )
            "clear_instructions" -> {
                AssistantStore.setInstructions(null)
                Result.success("Custom instructions cleared.")
            }
            else -> Result.failure(
                IllegalArgumentException(
                    "Unknown assistant_memory action '$action'. Use remember, forget, list, " +
                        "clear, set_instructions, show_instructions, or clear_instructions."
                )
            )
        }
    }

    private fun memorySummary(): String {
        val mems = AssistantStore.memories()
        if (mems.isEmpty()) return "No memories stored yet."
        return "Stored memories (${mems.size}/${AssistantStore.MAX_MEMORIES}): " +
            mems.joinToString("; ") { "\"${it.text}\"" }
    }
}
