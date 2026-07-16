package com.x3gemini.app.core.tools

import android.content.Context
import com.x3gemini.app.core.config.AssistantStore
import java.util.Locale

/**
 * custom_command — named saved prompts ("run my morning report").
 *
 * save  — store a prompt under a name ("morning report" → "give me the
 *         weather in Oakland, my reminders for today, and the top 3 AI
 *         headlines").
 * run   — fetch the stored prompt; the RESULT tells the model to carry
 *         it out immediately as if the user had just spoken it. That's
 *         the whole trick: the expansion happens in-session, so saved
 *         commands can use every other tool (search, pins, reminders).
 * list / delete — management.
 */
class CommandTool(private val context: Context) : AiTapTool {

    override val name = "custom_command"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        AssistantStore.init(context)
        return when (val action = (args["action"] ?: "").trim().lowercase(Locale.US)) {
            "save" -> {
                val name = (args["name"] ?: "").trim()
                val prompt = (args["prompt"] ?: "").trim()
                if (name.isBlank() || prompt.isBlank()) {
                    Result.failure(
                        IllegalArgumentException("save needs 'name' and 'prompt'.")
                    )
                } else if (AssistantStore.saveCommand(name, prompt)) {
                    Result.success(
                        "Saved the command \"$name\". The user can run it anytime by " +
                            "saying 'run my $name'."
                    )
                } else {
                    Result.success(
                        "Command list is full (${AssistantStore.MAX_COMMANDS}). " +
                            "Ask the user which to delete. ${listSummary()}"
                    )
                }
            }
            "run" -> {
                val name = (args["name"] ?: "").trim()
                val hit = AssistantStore.command(name)
                if (hit == null) {
                    Result.success("No saved command matches \"$name\". ${listSummary()}")
                } else {
                    Result.success(
                        "Saved command \"${hit.first}\" retrieved. NOW CARRY IT OUT " +
                            "COMPLETELY as if the user just said it, using any tools " +
                            "needed (web search, reminders, HUD pins):\n${hit.second}"
                    )
                }
            }
            "list" -> Result.success(listSummary())
            "delete" -> {
                val name = (args["name"] ?: "").trim()
                val removed = AssistantStore.deleteCommand(name)
                if (removed != null) Result.success("Deleted the command \"$removed\".")
                else Result.success("No saved command matches \"$name\". ${listSummary()}")
            }
            else -> Result.failure(
                IllegalArgumentException(
                    "Unknown custom_command action '$action'. Use save, run, list, or delete."
                )
            )
        }
    }

    private fun listSummary(): String {
        val cmds = AssistantStore.commands()
        if (cmds.isEmpty()) return "No custom commands saved yet."
        return "Saved commands (${cmds.size}/${AssistantStore.MAX_COMMANDS}): " +
            cmds.keys.joinToString(", ") { "\"$it\"" }
    }
}
