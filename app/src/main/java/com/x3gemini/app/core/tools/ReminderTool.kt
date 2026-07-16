package com.x3gemini.app.core.tools

import android.content.Context
import com.x3gemini.app.core.reminders.ReminderScheduler
import com.x3gemini.app.core.reminders.ReminderStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * reminder — "remind me to … at …". One-shot or daily-repeating.
 * Delivery = system notification + a ⏰ post-it pin on the HUD board.
 *
 * Time arrives from the model either as 'at' ("2026-07-15 08:00",
 * device-local — the model has the authoritative local clock in its
 * system prompt) or 'in_minutes'. The tool validates it's in the future.
 */
class ReminderTool(private val context: Context) : AiTapTool {

    override val name = "reminder"

    companion object {
        private val AT_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        private val SPOKEN_FORMAT = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        ReminderStore.init(context)
        return when (val action = (args["action"] ?: "").trim().lowercase(Locale.US)) {
            "set" -> set(args)
            "list" -> Result.success(summary())
            "cancel" -> {
                val query = (args["text"] ?: "").trim()
                val removed = ReminderStore.removeByText(query)
                if (removed != null) {
                    ReminderScheduler.cancel(context, removed.id)
                    Result.success("Canceled the reminder \"${removed.text}\".")
                } else {
                    Result.success("No reminder matches \"$query\". ${summary()}")
                }
            }
            else -> Result.failure(
                IllegalArgumentException("Unknown reminder action '$action'. Use set, list, or cancel.")
            )
        }
    }

    private fun set(args: Map<String, String>): Result<String> {
        val text = (args["text"] ?: "").trim()
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("reminder set needs 'text' — what to remind."))
        }
        val now = System.currentTimeMillis()
        val inMinutes = (args["in_minutes"] ?: "").trim().toDoubleOrNull()
        val atArg = (args["at"] ?: "").trim()
        val atMs: Long = when {
            inMinutes != null && inMinutes > 0 -> now + (inMinutes * 60_000L).toLong()
            atArg.isNotBlank() -> {
                val parsed = runCatching { AT_FORMAT.parse(atArg)?.time }.getOrNull()
                    ?: return Result.failure(
                        IllegalArgumentException(
                            "Couldn't parse at='$atArg'. Use device-local 'yyyy-MM-dd HH:mm' " +
                                "(compute it from the CURRENT DATE/TIME in your instructions) " +
                                "or pass in_minutes."
                        )
                    )
                parsed
            }
            else -> return Result.failure(
                IllegalArgumentException("reminder set needs 'at' (yyyy-MM-dd HH:mm local) or 'in_minutes'.")
            )
        }
        if (atMs <= now) {
            return Result.failure(
                IllegalArgumentException(
                    "That time (${SPOKEN_FORMAT.format(Date(atMs))}) is in the past — " +
                        "recompute from the current local time."
                )
            )
        }
        val repeatDaily = (args["repeat_daily"] ?: "").trim().equals("true", ignoreCase = true)
        val reminder = ReminderStore.Reminder(text = text.take(200), atMs = atMs, repeatDaily = repeatDaily)
        if (!ReminderStore.add(reminder)) {
            return Result.success(
                "Reminder list is full (${ReminderStore.MAX_REMINDERS}). Ask the user which to cancel. ${summary()}"
            )
        }
        ReminderScheduler.schedule(context, reminder)
        val whenText = SPOKEN_FORMAT.format(Date(atMs))
        return Result.success(
            "Reminder set for $whenText${if (repeatDaily) " (repeats daily)" else ""}: \"$text\". " +
                "It will pop a notification and pin itself to the HUD."
        )
    }

    private fun summary(): String {
        val all = ReminderStore.all()
        if (all.isEmpty()) return "No reminders are set."
        return "Reminders (${all.size}/${ReminderStore.MAX_REMINDERS}): " + all.joinToString("; ") {
            "\"${it.text}\" at ${SPOKEN_FORMAT.format(Date(it.atMs))}" +
                if (it.repeatDaily) " (daily)" else ""
        }
    }
}
