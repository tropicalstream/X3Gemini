package com.x3gemini.app.core.tools

/**
 * Interface for X3Gemini's native tool implementations. Each tool is
 * declared to Gemini Live and executed locally on the device.
 */
interface AiTapTool {
    /** Tool name as declared to Gemini (e.g. "hud_pin"). */
    val name: String

    /** Execute the tool with parsed arguments. Returns result text for Gemini. */
    suspend fun execute(args: Map<String, String>): Result<String>
}
