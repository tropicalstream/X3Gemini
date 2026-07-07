package com.x3gemini.app.core.tools

import android.content.Context
import android.util.Base64
import android.util.Log
import com.x3gemini.app.core.bridge.HudPinStore
import java.io.File
import java.util.Locale

/**
 * hud_pin — Gemini's write path onto the HUD pin board ("pin that to
 * my HUD"). State lives in [HudPinStore]; the board UI re-renders
 * through the store's observer, so this tool never touches views.
 *
 * X3Gemini keeps three pin types from TapInsight: post-it NOTES,
 * PICTURES (camera captures or image URLs), and LIVE cards (watch
 * queries refreshed by LiveCardEngine). add_icon (URL shortcuts) is
 * gone — there is no browser on this build.
 */
class HudPinTool(
    private val context: Context,
    /** Base64 JPEG of the current camera frame, null when the camera is off. */
    private val frameProvider: () -> String?
) : AiTapTool {

    override val name = "hud_pin"

    companion object {
        private const val TAG = "HudPinTool"
        private const val PIN_DIR = "hud_pins"
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        HudPinStore.init(context)
        return when (val action = (args["action"] ?: "").trim().lowercase(Locale.US)) {
            "add_note" -> addNote(args)
            "add_picture" -> addPicture(args)
            "add_live" -> addLive(args)
            "remove" -> remove(args)
            "list" -> list()
            "clear" -> {
                HudPinStore.clear()
                Result.success("Cleared all HUD pins.")
            }
            else -> Result.failure(
                IllegalArgumentException(
                    "Unknown hud_pin action '$action'. Use add_note, add_picture, " +
                        "add_live, remove, list, or clear."
                )
            )
        }
    }

    /**
     * Live card — a watch query over ANY source: "Warriors score",
     * "top AI headline", "changes to <url>". LiveCardEngine refreshes
     * it on interval; a sourceUrl scopes the watch to one page,
     * otherwise the engine answers with web-search grounding.
     */
    private fun addLive(args: Map<String, String>): Result<String> {
        val query = (args["query"] ?: args["text"] ?: "").trim()
        if (query.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "hud_pin add_live needs 'query' — what to watch, e.g. " +
                        "'Warriors score' or 'top AI headline'."
                )
            )
        }
        val source = (args["source"] ?: "").trim().takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
        val label = (args["label"] ?: "").trim().ifBlank {
            query.split(Regex("\\s+")).take(3).joinToString(" ")
        }.take(24)
        val intervalMin = (args["interval_minutes"] ?: "").trim()
            .toIntOrNull()?.coerceIn(1, 180) ?: 5
        com.x3gemini.app.core.live.LiveCardEngine.ensureStarted(context)
        val added = HudPinStore.add(
            HudPinStore.HudPin(
                type = HudPinStore.TYPE_LIVE,
                label = label,
                payload = query,
                sourceUrl = source,
                intervalSec = intervalMin * 60
            )
        )
        if (added) HudPinStore.all()
            .firstOrNull { it.type == HudPinStore.TYPE_LIVE && it.payload == query }
            ?.let { HudPinStore.requestRefresh(it.id) }
        return capacityResult(
            added,
            "Added the live card \"$label\" — it will refresh every $intervalMin minute(s)" +
                (source?.let { " from $it" } ?: " via web search") +
                ". First update is fetching now."
        )
    }

    private fun addNote(args: Map<String, String>): Result<String> {
        val text = (args["text"] ?: "").trim()
        if (text.isBlank()) {
            return Result.failure(
                IllegalArgumentException("hud_pin add_note needs 'text' — the note body.")
            )
        }
        val label = (args["label"] ?: "").trim().ifBlank {
            text.split(Regex("\\s+")).take(3).joinToString(" ")
        }.take(24)
        val added = HudPinStore.add(
            HudPinStore.HudPin(
                type = HudPinStore.TYPE_NOTE,
                label = label,
                payload = text.take(280)
            )
        )
        return capacityResult(added, "Posted the note \"$label\" to the HUD.")
    }

    private fun addPicture(args: Map<String, String>): Result<String> {
        val source = (args["source"] ?: "screen").trim()
        val label = (args["label"] ?: "").trim().ifBlank { "picture" }.take(24)
        val payload: String
        if (source.startsWith("http://") || source.startsWith("https://")) {
            payload = source
        } else {
            // "pin that picture" → save the current camera frame locally so
            // the pin survives the camera turning off.
            val base64 = frameProvider()
                ?: return Result.failure(
                    IllegalStateException(
                        "Couldn't capture a picture — the camera isn't streaming. " +
                            "The user can double-tap the left temple arm to start it."
                    )
                )
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: Exception) {
                return Result.failure(IllegalStateException("Camera capture decode failed."))
            }
            val dir = File(context.filesDir, PIN_DIR).apply { mkdirs() }
            val file = File(dir, "pin_${System.currentTimeMillis()}.jpg")
            try {
                file.writeBytes(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save pin capture: ${e.message}")
                return Result.failure(IllegalStateException("Couldn't save the capture."))
            }
            payload = file.absolutePath
        }
        val added = HudPinStore.add(
            HudPinStore.HudPin(type = HudPinStore.TYPE_PICTURE, label = label, payload = payload)
        )
        if (!added) {
            // don't leak the saved capture when the board is full
            if (!payload.startsWith("http")) runCatching { File(payload).delete() }
        }
        return capacityResult(
            added,
            "Pinned the picture \"$label\" to the HUD — tapping it opens it full screen."
        )
    }

    private fun remove(args: Map<String, String>): Result<String> {
        val query = (args["label"] ?: args["text"] ?: "").trim()
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("hud_pin remove needs 'label'."))
        }
        val removedLabel = HudPinStore.removeByLabel(query)
            ?: return Result.success(
                "No HUD pin matches \"$query\". Current pins: ${labelsSummary()}"
            )
        return Result.success("Removed the \"$removedLabel\" pin from the HUD.")
    }

    private fun list(): Result<String> {
        val pins = HudPinStore.all()
        if (pins.isEmpty()) return Result.success("The HUD pin board is empty.")
        val lines = pins.joinToString("; ") { "\"${it.label}\" (${it.type})" }
        return Result.success("HUD pins (${pins.size}/${HudPinStore.MAX_PINS}): $lines")
    }

    private fun labelsSummary(): String {
        val pins = HudPinStore.all()
        return if (pins.isEmpty()) "none" else pins.joinToString(", ") { "\"${it.label}\"" }
    }

    private fun capacityResult(added: Boolean, successText: String): Result<String> =
        if (added) {
            Result.success(successText)
        } else {
            Result.success(
                "The HUD pin board is full (${HudPinStore.MAX_PINS} pins). Ask the user " +
                    "which pin to remove first. Current pins: ${labelsSummary()}"
            )
        }
}
