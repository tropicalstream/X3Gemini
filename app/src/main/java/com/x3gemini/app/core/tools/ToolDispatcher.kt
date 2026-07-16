package com.x3gemini.app.core.tools

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Routes Gemini Live tool calls to X3Gemini's native tools:
 * camera_action, hud_pin, and the "real assistant" trio —
 * assistant_memory (memory + custom instructions), reminder
 * (notification + HUD delivery), and custom_command (saved prompts).
 * Slimmed from TapInsight's ToolDispatcher (which carried a dozen
 * Google/agent/browser tools).
 */
class ToolDispatcher(
    context: Context,
    cameraFrameProvider: () -> String?
) {

    companion object {
        private const val TAG = "ToolDispatcher"
    }

    private val tools: Map<String, AiTapTool> = listOf(
        CameraTool(context, cameraFrameProvider),
        HudPinTool(context, cameraFrameProvider),
        MemoryTool(context),
        ReminderTool(context),
        CommandTool(context)
    ).associateBy { it.name }

    fun isSupported(name: String): Boolean = tools.containsKey(name.trim())

    suspend fun dispatch(name: String, argsJson: String): Result<String> {
        val tool = tools[name.trim()]
            ?: return Result.failure(IllegalArgumentException("Unknown tool: $name"))
        val args = parseArgs(argsJson)
        Log.d(TAG, "dispatch $name args=${args.keys}")
        return runCatching { tool.execute(args) }.getOrElse { Result.failure(it) }
    }

    private fun parseArgs(argsJson: String): Map<String, String> {
        if (argsJson.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(argsJson)
            val out = mutableMapOf<String, String>()
            for (key in obj.keys()) {
                out[key] = obj.opt(key)?.toString().orEmpty()
            }
            out
        }.getOrDefault(emptyMap())
    }
}
