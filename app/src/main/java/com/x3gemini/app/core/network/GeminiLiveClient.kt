package com.x3gemini.app.core.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Gemini Live WebSocket client for X3Gemini. Ported from TapInsight's
 * GeminiRouter and pruned hard: direct-to-Google only (no gateway), one
 * hardcoded model (gemini-2.5-flash-native-audio-preview-12-2025, the
 * model validated end-to-end on the X3 glasses), default voice (no
 * speechConfig), barge-in enabled via default-sensitivity server VAD,
 * and exactly three tool surfaces: camera_action, hud_pin, googleSearch.
 *
 * New vs TapInsight: the `serverContent.interrupted` event is parsed and
 * surfaced as [LiveSessionListener.onInterrupted] so the pipeline can
 * flush queued playback the moment the user barges in.
 */
class GeminiLiveClient(
    private val apiKeyProvider: () -> String?,
    private val previousChatContextProvider: () -> String? = { null },
    /**
     * Personalization block (custom instructions + memories + saved command
     * names) from AssistantStore — appended to the system prompt every
     * session, which is what makes memory persist across the stateless
     * Live connections.
     */
    private val personalizationProvider: () -> String? = { null }
) {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val LIVE_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** The one Live model X3Gemini speaks — Gemini 2.5 Flash native audio. */
        const val LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"

        private const val SYSTEM_PROMPT_BASE =
            "You are X3Gemini, a voice assistant running on RayNeo X3 Pro AR glasses. " +
                "You hear the user through the glasses microphone and, when the camera is " +
                "streaming, you see what they see through the glasses camera.\n\n" +
                "CAPABILITIES:\n" +
                "- Conversation: answer questions naturally and BRIEFLY — replies are spoken " +
                "aloud and shown on a small heads-up card, so keep them short and information-dense.\n" +
                "- Vision: when camera frames are streaming, use them to answer 'look at this' / " +
                "'what does this say' / 'describe this' style questions about the real world.\n" +
                "- camera_action: save a photo of what the camera sees (action=save_photo).\n" +
                "- hud_pin: manage the HUD pin board — post-it notes, pictures, and live " +
                "auto-refreshing info cards placed on the display.\n" +
                "- assistant_memory: PERSISTENT MEMORY and CUSTOM INSTRUCTIONS. When the user " +
                "says 'remember …' or states a clearly durable personal fact or preference " +
                "(their name, home city, dietary needs, 'I always …'), call action=remember " +
                "with a one-line summary and confirm briefly. Never store one-off trivia. " +
                "When the user personalizes you ('from now on always …', 'call me …', 'be " +
                "more concise'), call action=set_instructions with the full instruction text " +
                "and follow it immediately.\n" +
                "- reminder: set/list/cancel reminders ('remind me to … at …', 'remind me in " +
                "20 minutes'). Delivery is a notification plus a ⏰ pin on the HUD. Compute " +
                "'at' from the CURRENT DATE/TIME given below (device-local, yyyy-MM-dd HH:mm), " +
                "or pass in_minutes. repeat_daily=true for 'every day/morning/night'.\n" +
                "- custom_command: saved named prompts. 'Save a command called X that does Y' " +
                "→ action=save. 'Run my X' → action=run, then CARRY OUT the returned prompt " +
                "completely (it may use search, reminders, pins). Great for a morning report.\n" +
                "- Web search grounding is available for current information.\n\n" +
                "CONSTRAINTS:\n" +
                "- There is NO web browser on this device. You cannot open web pages, play " +
                "videos or music, or post links. Never offer to open or show a URL; if the user " +
                "asks, explain there is no browser and offer a spoken answer or a HUD pin instead.\n" +
                "- Do not read URLs aloud.\n" +
                "- Answer in the language the user speaks to you."

        /** All known Gemini prebuilt voice names — unused (default voice), kept
         *  for reference should a voice ever be pinned. */
        @Suppress("unused")
        private val KNOWN_VOICES = setOf(
            "Puck", "Charon", "Kore", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr"
        )
    }

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Client-initiated pings killed healthy long responses with
            // "no pong response" — leave ping handling to the server.
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    interface LiveSessionListener {
        fun onSessionReady()
        fun onInputTranscription(text: String)
        fun onOutputTranscription(text: String)
        fun onModelText(text: String)
        fun onModelAudio(mimeType: String, data: ByteArray)
        fun onToolCall(callId: String, name: String, args: String)
        fun onTurnComplete(finishReason: String?)
        /** User barged in while the model was speaking — flush playback. */
        fun onInterrupted() {}
        fun onError(message: String)
        fun onClosed(code: Int, reason: String)
    }

    class LiveSessionHandle internal constructor(
        private val socket: WebSocket
    ) {
        fun sendAudioChunkPcm16(bytes: ByteArray, size: Int, sampleRateHz: Int = 16_000): Boolean {
            if (size <= 0) return false
            val chunk = JSONObject()
                .put("mimeType", "audio/pcm;rate=$sampleRateHz")
                .put("data", Base64.getEncoder().encodeToString(bytes.copyOf(size)))
            val payload = JSONObject()
                .put("realtimeInput", JSONObject().put("audio", chunk))
            return socket.send(payload.toString())
        }

        fun sendImageChunkBase64(imageBase64: String, mimeType: String = "image/jpeg"): Boolean {
            if (imageBase64.isBlank()) return false
            val videoChunk = JSONObject()
                .put("mimeType", mimeType)
                .put("data", imageBase64)
            val payload = JSONObject().put(
                "realtimeInput",
                JSONObject().put("video", videoChunk)
            )
            return socket.send(payload.toString())
        }

        /** Inject a text message into the Live session as client context. */
        fun sendClientText(text: String): Boolean {
            if (text.isBlank()) return false
            val payload = JSONObject().put(
                "clientContent",
                JSONObject()
                    .put("turns", JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(
                                JSONObject().put("text", text)
                            ))
                    ))
                    .put("turnComplete", true)
            )
            return socket.send(payload.toString())
        }

        fun sendToolResponse(callId: String, functionName: String, result: String): Boolean {
            if (callId.isBlank() || functionName.isBlank()) return false
            val functionResponse = JSONObject()
                .put("id", callId)
                .put("name", functionName)
                .put("response", JSONObject().put("result", result))
            // Gemini Live expects exactly one top-level key: toolResponse
            // (camelCase). Mixing snake_case in corrupts the frame.
            val payload = JSONObject().put(
                "toolResponse",
                JSONObject().put("functionResponses", JSONArray().put(functionResponse))
            )
            return socket.send(payload.toString())
        }

        fun close() {
            socket.close(1000, "client_close")
        }
    }

    fun startLiveAudioSession(listener: LiveSessionListener): LiveSessionHandle? {
        val apiKey = apiKeyProvider()?.trim()?.takeIf { it.isNotBlank() }
        if (apiKey == null) {
            listener.onError("Gemini API key missing — push it via adb (see README).")
            return null
        }

        val request = Request.Builder()
            .url("$LIVE_WS_URL?key=$apiKey")
            .build()
        Log.d(TAG, "Starting Gemini Live model=$LIVE_MODEL")

        val socket = wsClient.newWebSocket(request, object : WebSocketListener() {
            private var setupReady = false
            private var setupSent = false

            private fun notifySetupReady() {
                if (setupReady) return
                setupReady = true
                listener.onSessionReady()
            }

            private fun sendSetup(webSocket: WebSocket): Boolean {
                if (setupSent) return true
                val effectivePrompt = buildString {
                    append(SYSTEM_PROMPT_BASE)
                    // Personalization (custom instructions, memories, saved
                    // commands) — the persistence layer the Live API lacks.
                    personalizationProvider()?.trim()?.takeIf { it.isNotBlank() }?.let {
                        append(it)
                    }
                    // Authoritative local date/time from the device clock —
                    // without it the model answers in UTC (TapInsight lesson).
                    run {
                        val now = java.util.Date()
                        val zone = java.util.TimeZone.getDefault()
                        val ts = java.text.SimpleDateFormat(
                            "EEEE, MMMM d, yyyy h:mm a",
                            java.util.Locale.US
                        ).apply { timeZone = zone }.format(now)
                        append("\n\nCURRENT DATE/TIME:\n")
                        append("It is currently $ts (timezone ${zone.id}). ")
                        append("This is the authoritative local date and time from the device clock. ")
                        append("Always use it when asked the time or date; never convert to UTC.")
                    }
                    val prevContext = previousChatContextProvider()?.trim().orEmpty()
                    if (prevContext.isNotBlank()) {
                        append("\n\nPREVIOUS CONVERSATION (fallback context from an earlier ")
                        append("session, NOT the current turn):\n")
                        append("Use these earlier assistant replies ONLY when the user clearly ")
                        append("refers back to them ('what did you say about…', 'tell me more ")
                        append("about that'). Never recite them unasked, never re-answer a ")
                        append("question verbatim from them, and never take tool-call arguments ")
                        append("from them — tool arguments come from the CURRENT turn only.\n\n")
                        append(prevContext)
                    }
                }

                val genConfig = JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    // Full-detail camera frames: MEDIA_RESOLUTION_MEDIUM
                    // downscales too far for reading signs/menus/fine print.
                    .put("mediaResolution", "MEDIA_RESOLUTION_HIGH")
                // Default voice per Mars's spec — no speechConfig block.

                val setupContent = JSONObject()
                    .put("model", "models/$LIVE_MODEL")
                    .put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", effectivePrompt))
                        )
                    )
                    .put("generationConfig", genConfig)
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject())
                    .put("tools", JSONArray()
                        .put(JSONObject().put("functionDeclarations", buildToolDeclarations()))
                        .put(JSONObject().put("googleSearch", JSONObject()))
                    )

                // Barge-in ENABLED: default-sensitivity server VAD. HIGH/HIGH
                // + 300ms is TapInsight's proven sensitivity=1.0 mapping.
                val realtimeInputConfig = JSONObject().put(
                    "automaticActivityDetection", JSONObject()
                        .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                        .put("silenceDurationMs", 300)
                )
                setupContent.put("realtimeInputConfig", realtimeInputConfig)

                val setup = JSONObject().put("setup", setupContent)
                Log.d(TAG, "Gemini Live setup payload: ${setup.toString().take(400)}")
                val sent = webSocket.send(setup.toString())
                if (!sent) {
                    listener.onError("Failed to send Gemini Live setup message.")
                    return false
                }
                setupSent = true
                return true
            }

            /** Completion flags arrive as boolean, "true", or an object
             *  with inner complete/turnComplete booleans (TapInsight port). */
            private fun JSONObject.optCompletionFlag(vararg keys: String): Boolean {
                for (key in keys) {
                    val value = opt(key) ?: continue
                    when (value) {
                        is Boolean -> if (value) return true
                        is String -> if (value.equals("true", ignoreCase = true)) return true
                        is JSONObject -> {
                            if (value.optBoolean("complete", false) ||
                                value.optBoolean("completed", false) ||
                                value.optBoolean("turnComplete", false) ||
                                value.optBoolean("turn_complete", false)
                            ) {
                                return true
                            }
                        }
                    }
                }
                return false
            }

            private fun handleLiveMessage(decoded: String) {
                // Truncated inbound trace. Audio frames are huge base64, so
                // collapse them to a marker; everything else logs verbatim
                // (this is how we see turn-taking on follow-ups).
                if (decoded.contains("\"inlineData\"") && decoded.contains("\"audio/")) {
                    Log.d(TAG, "inbound: <audio frame ${decoded.length} chars>")
                } else {
                    Log.d(TAG, "inbound: ${decoded.take(300)}")
                }
                runCatching {
                    val root = JSONObject(decoded)

                    val error = root.optJSONObject("error")
                    if (error != null) {
                        val msg = error.optString("message", "Gemini Live returned an error.")
                        listener.onError(msg)
                        return@runCatching
                    }

                    if (root.has("setupComplete") || root.has("setup_complete")) {
                        notifySetupReady()
                    }

                    val serverContent = root.optJSONObject("serverContent")
                        ?: root.optJSONObject("server_content")
                    if (serverContent != null) {
                        notifySetupReady()

                        // Barge-in: the model was interrupted by user speech.
                        // Queued audio on the client must be flushed NOW.
                        if (serverContent.optBoolean("interrupted", false)) {
                            listener.onInterrupted()
                        }

                        val inputTx = (serverContent
                            .optJSONObject("inputTranscription")
                            ?: serverContent.optJSONObject("input_transcription"))
                            ?.optString("text", "")
                            .orEmpty()
                            .trim()
                        if (inputTx.isNotBlank()) {
                            listener.onInputTranscription(inputTx)
                        }

                        val outputTx = (serverContent
                            .optJSONObject("outputTranscription")
                            ?: serverContent.optJSONObject("output_transcription"))
                            ?.optString("text", "")
                            .orEmpty()
                            .trim()
                        if (outputTx.isNotBlank()) {
                            listener.onOutputTranscription(outputTx)
                        }

                        val parts = (serverContent
                            .optJSONObject("modelTurn")
                            ?: serverContent.optJSONObject("model_turn"))
                            ?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.optJSONObject(i) ?: continue
                                val textPart = part.optString("text", "").trim()
                                if (textPart.isNotBlank()) {
                                    listener.onModelText(textPart)
                                }
                                val inlineData = part.optJSONObject("inlineData")
                                    ?: part.optJSONObject("inline_data")
                                if (inlineData != null) {
                                    val mime = inlineData.optString("mimeType", "")
                                    val encoded = inlineData.optString("data", "")
                                    if (mime.startsWith("audio/") && encoded.isNotBlank()) {
                                        val audioBytes = Base64.getDecoder().decode(encoded)
                                        listener.onModelAudio(mime, audioBytes)
                                    }
                                }
                            }
                        }

                        val turnComplete = serverContent.optCompletionFlag(
                            "turnComplete",
                            "turn_complete",
                            "generationComplete",
                            "generation_complete"
                        )
                        if (turnComplete) {
                            listener.onTurnComplete(null)
                        }
                    }

                    val toolCall = root.optJSONObject("toolCall")
                        ?: root.optJSONObject("tool_call")
                    if (toolCall != null) {
                        val functionCalls = toolCall.optJSONArray("functionCalls")
                            ?: toolCall.optJSONArray("function_calls")
                        if (functionCalls != null) {
                            for (i in 0 until functionCalls.length()) {
                                val call = functionCalls.optJSONObject(i) ?: continue
                                val functionCall = call.optJSONObject("functionCall")
                                    ?: call.optJSONObject("function_call")
                                val callId = sequenceOf(
                                    call.optString("id", "").trim(),
                                    call.optString("callId", "").trim(),
                                    functionCall?.optString("id", "")?.trim().orEmpty()
                                ).firstOrNull { it.isNotBlank() }
                                    ?: "tool-call-${System.currentTimeMillis()}-$i"
                                val name = sequenceOf(
                                    functionCall?.optString("name", "")?.trim().orEmpty(),
                                    call.optString("name", "").trim()
                                ).firstOrNull { it.isNotBlank() }.orEmpty()
                                val args = sequenceOf(
                                    functionCall?.optJSONObject("args")?.toString().orEmpty(),
                                    functionCall?.optString("args", "")?.trim().orEmpty(),
                                    call.optJSONObject("args")?.toString().orEmpty(),
                                    call.optString("args", "")?.trim().orEmpty()
                                ).firstOrNull { it.isNotBlank() }.orEmpty()
                                if (name.isNotBlank()) {
                                    listener.onToolCall(callId, name, args)
                                }
                            }
                        }
                    }
                }.onFailure {
                    listener.onError("Failed to parse Live response: ${it.message}")
                }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Gemini Live websocket opened")
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleLiveMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val decoded = runCatching { bytes.utf8() }.getOrNull()
                if (!decoded.isNullOrBlank()) {
                    handleLiveMessage(decoded)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gemini Live closed code=$code reason=$reason")
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Gemini Live failure: ${t.message}")
                listener.onError(t.message ?: "Gemini Live connection failed.")
            }
        })

        return LiveSessionHandle(socket)
    }

    /** Tool declarations: camera_action + hud_pin (no add_icon — no browser). */
    private fun buildToolDeclarations(): JSONArray {
        val tools = JSONArray()

        tools.put(JSONObject()
            .put("name", "camera_action")
            .put("description",
                "Save a photo of what the glasses camera currently sees. Use when the user " +
                    "says 'take a picture', 'save a photo', 'capture this'. The camera must " +
                    "be streaming; if it isn't, tell the user to double-tap the left temple arm.")
            .put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING")
                        .put("description", "Action: save_photo."))
                    .put("title", JSONObject().put("type", "STRING")
                        .put("description", "Optional title or label for the saved photo.")))
                .put("required", JSONArray().put("action"))))

        tools.put(JSONObject()
            .put("name", "hud_pin")
            .put("description",
                "Manage the user's HUD pin board — small pinned items shown on the heads-up " +
                    "display under the clock strip: post-it notes, pictures, and live " +
                    "auto-refreshing info cards. " +
                    "Use when the user says 'make a note', 'pin/post/add ... to my HUD', " +
                    "'put that on my HUD', 'remove the ... pin', 'what's pinned', 'clear my pins'. " +
                    "Actions: " +
                    "add_note — pin a post-it note; pass the note body in 'text'. " +
                    "add_picture — pin a picture; source='screen' captures what the camera " +
                    "currently sees (e.g. 'pin that picture of a cat'), or pass an https image URL. " +
                    "add_live — pin a LIVE card that auto-refreshes: sports scores, news topics, " +
                    "headlines, weather, prices — ANY watchable info. Pass the watch request in " +
                    "'query' (e.g. 'Warriors score', 'top AI headline'); optional 'interval_minutes' " +
                    "(default 5). Use add_live ONLY for information that CHANGES over time. " +
                    "remove — delete a pin by its (approximate) label. list — say what's pinned. " +
                    "clear — remove all pins. " +
                    "Moving and deleting pins is ALSO possible by hand: the user double-taps a pin " +
                    "with the trackpad cursor over it — mention that if they ask how to rearrange. " +
                    "After the tool returns, tell the user in one short sentence what happened.")
            .put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING")
                        .put("description", "One of: add_note, add_picture, add_live, remove, list, clear."))
                    .put("label", JSONObject().put("type", "STRING")
                        .put("description", "Short display label (max ~24 chars). For remove: the label to delete (fuzzy matched)."))
                    .put("text", JSONObject().put("type", "STRING")
                        .put("description", "add_note only: the note body text."))
                    .put("source", JSONObject().put("type", "STRING")
                        .put("description", "add_picture: 'screen' (default, captures the camera view) or an https image URL. add_live: optional http(s) URL of a page to watch."))
                    .put("query", JSONObject().put("type", "STRING")
                        .put("description", "add_live only: what to watch, in plain language."))
                    .put("interval_minutes", JSONObject().put("type", "STRING")
                        .put("description", "add_live only: refresh cadence in minutes, 1-180. Default 5.")))
                .put("required", JSONArray().put("action"))))

        tools.put(JSONObject()
            .put("name", "assistant_memory")
            .put("description",
                "Persistent memory and custom instructions — they survive across sessions. " +
                    "remember — store a durable fact the user shares or asks you to keep " +
                    "('remember that my car is parked on level 3', 'my name is Mars'); pass a " +
                    "concise one-line summary in 'text'. " +
                    "forget — delete the memory matching 'text'. " +
                    "list — recite stored memories. clear — delete all memories. " +
                    "set_instructions — save persistent personalization when the user says " +
                    "'from now on…', 'always…', 'call me…', 'act like…'; pass the complete " +
                    "instruction text in 'text' (replaces previous instructions, so merge in " +
                    "anything the user wants kept). " +
                    "show_instructions / clear_instructions — inspect or remove them. " +
                    "After the tool returns, confirm in one short sentence.")
            .put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING")
                        .put("description", "One of: remember, forget, list, clear, set_instructions, show_instructions, clear_instructions."))
                    .put("text", JSONObject().put("type", "STRING")
                        .put("description", "remember/forget: the fact. set_instructions: the full instruction text.")))
                .put("required", JSONArray().put("action"))))

        tools.put(JSONObject()
            .put("name", "reminder")
            .put("description",
                "Reminders with real delivery: at the set time the glasses show a system " +
                    "notification AND pin a ⏰ note to the HUD. " +
                    "set — needs 'text' (what to remind) plus either 'at' (device-LOCAL time " +
                    "'yyyy-MM-dd HH:mm', computed from the CURRENT DATE/TIME in your system " +
                    "instructions) or 'in_minutes' (e.g. '20'). Optional repeat_daily='true' " +
                    "for 'every day / every morning'. " +
                    "list — recite pending reminders. cancel — remove the one matching 'text'. " +
                    "After the tool returns, confirm the scheduled time in one short sentence.")
            .put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING")
                        .put("description", "One of: set, list, cancel."))
                    .put("text", JSONObject().put("type", "STRING")
                        .put("description", "What to remind (set) or which reminder to cancel (fuzzy matched)."))
                    .put("at", JSONObject().put("type", "STRING")
                        .put("description", "Local fire time 'yyyy-MM-dd HH:mm' (24h). Alternative to in_minutes."))
                    .put("in_minutes", JSONObject().put("type", "STRING")
                        .put("description", "Fire this many minutes from now. Alternative to at."))
                    .put("repeat_daily", JSONObject().put("type", "STRING")
                        .put("description", "'true' to repeat every 24h (daily standup, morning report).")))
                .put("required", JSONArray().put("action"))))

        tools.put(JSONObject()
            .put("name", "custom_command")
            .put("description",
                "Saved named prompts the user can trigger by name — e.g. a personalized " +
                    "morning report. " +
                    "save — store 'prompt' under 'name' ('save a command called morning " +
                    "report that gives me the weather, my reminders, and top AI news'). " +
                    "run — retrieve the prompt for 'name' (fuzzy matched); the result " +
                    "contains the prompt — then EXECUTE it fully as if the user just said " +
                    "it, using web search and any tools it needs. " +
                    "list — recite saved command names. delete — remove one by 'name'. " +
                    "Tip: pair with reminder repeat_daily to nudge the user to run it.")
            .put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING")
                        .put("description", "One of: save, run, list, delete."))
                    .put("name", JSONObject().put("type", "STRING")
                        .put("description", "The command's name, e.g. 'morning report'."))
                    .put("prompt", JSONObject().put("type", "STRING")
                        .put("description", "save only: the full prompt text to store.")))
                .put("required", JSONArray().put("action"))))

        return tools
    }
}
