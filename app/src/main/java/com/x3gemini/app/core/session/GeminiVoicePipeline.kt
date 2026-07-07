package com.x3gemini.app.core.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.x3gemini.app.X3GeminiApp
import com.x3gemini.app.core.audio.GeminiAudioPlayer
import com.x3gemini.app.core.bridge.HudStateBridge
import com.x3gemini.app.core.config.ApiKeyStore
import com.x3gemini.app.core.network.GeminiLiveClient
import com.x3gemini.app.core.tools.ToolDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Gemini Live voice pipeline, ported from TapInsight unipanel and
 * pruned to X3Gemini's surface: audio in/out, camera frames, and the
 * camera_action / hud_pin tools. Runs inside the bound voice Service.
 *
 * X3Gemini changes vs TapInsight:
 *   • Barge-in is ON: the mic streams during Gemini's reply, and the
 *     `interrupted` event flushes queued playback immediately.
 *   • 5-second mutual-silence auto-end: whenever NEITHER side is
 *     speaking (no user speech on the mic, no model audio playing, no
 *     tool in flight) for 5 continuous seconds, the session closes.
 *   • Chat context for follow-ups is RAM-only (ChatSessionModel).
 *
 * Lifecycle: created by [GeminiSessionForegroundService] in onCreate,
 * torn down in onDestroy. [activate]/[shutdown] are idempotent.
 */
class GeminiVoicePipeline(context: Context) {

    private val appContext: Context = context.applicationContext

    private val chat by lazy { (appContext as X3GeminiApp).chatModel }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null
    private var silenceWatchdogJob: Job? = null

    @Volatile private var liveSession: GeminiLiveClient.LiveSessionHandle? = null
    @Volatile private var liveSessionReady: Boolean = false
    @Volatile private var captureActive: Boolean = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioThread: Thread? = null
    @Volatile private var latestInputTranscript: String = ""
    @Volatile private var latestCameraFrame: String? = null
    @Volatile private var lastCameraFrameMs: Long = 0L
    @Volatile private var activeSessionEpoch: Long = 0L

    /** Last moment either side was "active" — see the watchdog. */
    @Volatile private var lastConversationActivityMs: Long = 0L
    private val toolCallsInFlight = AtomicInteger(0)

    /**
     * Late-output gate. Its only job is to drop duplicate
     * outputTranscription chunks that arrive in the brief window right
     * AFTER onTurnComplete (a known Live quirk that otherwise appends a
     * duplicate assistant card).
     *
     * This is a TIME-BOUNDED window, not "until the next input turn":
     * native-audio Gemini often omits inputTranscription entirely, so a
     * "clear on next input" gate would latch forever and silently drop
     * every follow-up's transcript after turn 1. Expiring by time can't.
     */
    @Volatile private var dropLateOutputUntilMs: Long = 0L

    private val audioPlayer: GeminiAudioPlayer by lazy { GeminiAudioPlayer(appContext) }

    private val liveClient: GeminiLiveClient by lazy {
        GeminiLiveClient(
            apiKeyProvider = { ApiKeyStore.resolve(appContext) },
            previousChatContextProvider = { chat.getPreviousChatContext() }
        )
    }

    private val toolDispatcher: ToolDispatcher by lazy {
        ToolDispatcher(
            context = appContext,
            // Freshness-gated: a frame only counts while the feed is live
            // (frames stream ~1.1s apart) so save_photo / add_picture
            // can't silently capture a stale shot after the camera stops.
            cameraFrameProvider = {
                latestCameraFrame?.takeIf {
                    SystemClock.elapsedRealtime() - lastCameraFrameMs < CAMERA_FRESH_WINDOW_MS
                }
            }
        )
    }

    /**
     * Begin a voice session. Connects the WebSocket; AudioRecord opens
     * after onSessionReady so we don't stream into a not-ready socket.
     * Idempotent.
     */
    fun activate() {
        if (captureActive || liveSession != null || connectJob?.isActive == true) {
            Log.d(TAG, "activate(): already in progress / active, skipping")
            return
        }

        if (ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "activate(): RECORD_AUDIO not granted")
            HudStateBridge.update {
                it.copy(
                    phase = HudStateBridge.VoicePhase.IDLE,
                    connection = HudStateBridge.ConnectionStatus.ERROR,
                    notification = "Microphone permission needed."
                )
            }
            return
        }

        // Distinguish "no key" from "connection failed" up front, so the
        // HUD gives the real reason instead of a generic connect error.
        val resolvedKey = ApiKeyStore.resolve(appContext)
        if (resolvedKey.isNullOrBlank()) {
            Log.w(TAG, "activate(): no Gemini API key resolved")
            HudStateBridge.update {
                it.copy(
                    phase = HudStateBridge.VoicePhase.IDLE,
                    connection = HudStateBridge.ConnectionStatus.ERROR,
                    notification = "No Gemini API key — push it via adb (see README)."
                )
            }
            return
        }
        Log.i(TAG, "activate(): starting voice session (key len=${resolvedKey.length})")
        runCatching { chat.resetLiveAssistantStream() }

        HudStateBridge.update {
            it.copy(
                phase = HudStateBridge.VoicePhase.LISTENING,
                connection = HudStateBridge.ConnectionStatus.CONNECTING,
                transcript = "Connecting…",
                notification = null,
                oscilloscopeLevel = 0f
            )
        }

        val epoch = beginSessionEpoch()
        val listener = createListener(epoch)
        connectJob = scope.launch {
            val handle = runCatching {
                liveClient.startLiveAudioSession(listener)
            }.getOrNull()

            if (handle == null) {
                Log.w(TAG, "startLiveAudioSession returned null")
                HudStateBridge.update {
                    it.copy(
                        phase = HudStateBridge.VoicePhase.IDLE,
                        connection = HudStateBridge.ConnectionStatus.ERROR,
                        notification = "Could not connect to Gemini Live."
                    )
                }
                return@launch
            }

            if (!isSessionEpochCurrent(epoch)) {
                Log.i(TAG, "Live session handle acquired for stale epoch=$epoch; closing")
                runCatching { handle.close() }
                return@launch
            }

            liveSession = handle
            Log.i(TAG, "Live session handle acquired; awaiting onSessionReady")
        }
    }

    /**
     * End the current voice session. Tears down AudioRecord, closes the
     * WebSocket, stops playback, publishes IDLE. Idempotent, any thread.
     */
    fun shutdown(reason: String? = null) {
        invalidateSessionEpoch()
        Log.i(TAG, "shutdown(reason=$reason)")

        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null

        captureActive = false
        val thread = audioThread
        audioThread = null
        runCatching { thread?.interrupt() }

        val rec = audioRecord
        audioRecord = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }

        val session = liveSession
        liveSession = null
        liveSessionReady = false
        runCatching { session?.close() }

        connectJob?.cancel()
        connectJob = null
        dropLateOutputUntilMs = 0L

        // Releasing the AudioTrack beats pause/flush in the service path:
        // stale Live callbacks can't keep speaking on a detached track.
        runCatching { audioPlayer.release() }

        // Commit any pending assistant chunk so it stays on the card, and
        // snapshot the exchange (RAM only) for next-session follow-ups.
        runCatching { chat.appendUserUtterance(latestInputTranscript) }
        runCatching { chat.commitLiveAssistantStreamIfNeeded() }
        runCatching { chat.saveChatContextForNextSession() }
        runCatching { chat.resetLiveAssistantStream() }

        HudStateBridge.update {
            it.copy(
                phase = HudStateBridge.VoicePhase.IDLE,
                connection = HudStateBridge.ConnectionStatus.IDLE,
                transcript = null,
                oscilloscopeLevel = 0f,
                notification = reason
            )
        }
    }

    /** Release everything. Called from Service.onDestroy. */
    fun release() {
        shutdown(reason = null)
        runCatching { audioPlayer.release() }
    }

    /**
     * Push one camera frame into the active Gemini Live session. Called
     * by the Service whenever FrameCaptureManager produces a frame.
     */
    fun sendCameraFrame(base64: String) {
        latestCameraFrame = base64.takeIf { it.isNotBlank() }
        if (base64.isNotBlank()) lastCameraFrameMs = SystemClock.elapsedRealtime()
        if (!liveSessionReady) return
        if (base64.isBlank()) return
        runCatching {
            liveSession?.sendImageChunkBase64(base64, "image/jpeg")
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Internals
    // ────────────────────────────────────────────────────────────────

    @Synchronized
    private fun beginSessionEpoch(): Long {
        activeSessionEpoch += 1L
        return activeSessionEpoch
    }

    @Synchronized
    private fun invalidateSessionEpoch() {
        activeSessionEpoch += 1L
    }

    private fun isSessionEpochCurrent(epoch: Long): Boolean =
        activeSessionEpoch == epoch

    private fun noteConversationActivity() {
        lastConversationActivityMs = SystemClock.uptimeMillis()
    }

    private fun createListener(epoch: Long): GeminiLiveClient.LiveSessionListener {
        return object : GeminiLiveClient.LiveSessionListener {
            override fun onSessionReady() {
                if (!isSessionEpochCurrent(epoch)) return
                Log.i(TAG, "onSessionReady")
                liveSessionReady = true
                noteConversationActivity()
                HudStateBridge.update {
                    it.copy(
                        connection = HudStateBridge.ConnectionStatus.GEMINI_CONNECTED,
                        transcript = "Listening…",
                        notification = null
                    )
                }
                startAudioStreaming(epoch)
                startSilenceWatchdog(epoch)
            }

            override fun onInputTranscription(text: String) {
                if (!isSessionEpochCurrent(epoch)) return
                if (text.isBlank()) return
                noteConversationActivity()
                latestInputTranscript = text
                Log.d(TAG, "onInputTranscription: '${text.take(120)}'")
                // Live partial transcript in the HUD; final commit happens
                // on turnComplete to avoid mid-utterance noise.
                HudStateBridge.update { it.copy(transcript = text) }
            }

            override fun onOutputTranscription(text: String) {
                if (!isSessionEpochCurrent(epoch)) return
                if (text.isBlank()) return
                noteConversationActivity()
                if (SystemClock.uptimeMillis() < dropLateOutputUntilMs) {
                    Log.d(TAG, "Dropping late outputTranscription (post-turn window): '${text.take(120)}'")
                    return
                }
                Log.d(TAG, "onOutputTranscription: '${text.take(120)}'")
                runCatching { chat.appendLiveAssistantStreamChunk(text) }
                HudStateBridge.update {
                    it.copy(phase = HudStateBridge.VoicePhase.THINKING)
                }
            }

            override fun onModelText(text: String) {
                if (!isSessionEpochCurrent(epoch)) return
                if (text.isNotBlank()) noteConversationActivity()
                // Audio responses arrive via onOutputTranscription instead.
            }

            override fun onModelAudio(mimeType: String, data: ByteArray) {
                if (!isSessionEpochCurrent(epoch) || !liveSessionReady) return
                if (data.isEmpty()) return
                noteConversationActivity()
                Log.d(TAG, "onModelAudio: ${data.size} bytes ($mimeType)")
                runCatching {
                    audioPlayer.playChunk(mimeType, data, muted = false, volume = 1f)
                }
                // Drive the MODEL (green) glow from Gemini's outgoing audio.
                runCatching {
                    val peak = calculatePcm16Peak(data, data.size)
                    val norm = (peak / 32_767f).coerceIn(0f, 1f)
                    HudStateBridge.update {
                        it.copy(
                            oscilloscopeLevel = norm,
                            oscilloscopeChannel = HudStateBridge.OscilloscopeChannel.MODEL
                        )
                    }
                }
            }

            override fun onInterrupted() {
                if (!isSessionEpochCurrent(epoch)) return
                // User barged in — cut the queued reply audio NOW so the
                // model actually falls silent instead of draining buffers.
                Log.i(TAG, "onInterrupted: user barge-in, flushing playback")
                noteConversationActivity()
                runCatching { audioPlayer.stopAndFlush() }
                HudStateBridge.update {
                    it.copy(
                        phase = HudStateBridge.VoicePhase.LISTENING,
                        oscilloscopeLevel = 0f
                    )
                }
            }

            override fun onToolCall(callId: String, name: String, args: String) {
                if (!isSessionEpochCurrent(epoch)) return
                noteConversationActivity()
                Log.i(TAG, "onToolCall: callId=$callId name=$name args=${args.take(160)}")
                dispatchNativeTool(callId, name, args, epoch)
            }

            override fun onTurnComplete(finishReason: String?) {
                if (!isSessionEpochCurrent(epoch)) return
                noteConversationActivity()
                Log.d(TAG, "onTurnComplete: finishReason=$finishReason")
                dropLateOutputUntilMs = SystemClock.uptimeMillis() + LATE_OUTPUT_DROP_MS
                runCatching { chat.appendUserUtterance(latestInputTranscript) }
                runCatching { chat.commitLiveAssistantStreamIfNeeded() }
                runCatching { chat.resetLiveAssistantStream() }
                runCatching { audioPlayer.notifyTurnComplete() }
                if (liveSessionReady) {
                    HudStateBridge.update {
                        it.copy(
                            phase = HudStateBridge.VoicePhase.LISTENING,
                            transcript = "Listening…"
                        )
                    }
                }
            }

            override fun onError(message: String) {
                if (!isSessionEpochCurrent(epoch)) return
                Log.w(TAG, "onError: $message")
                shutdown(reason = "Voice error: $message")
            }

            override fun onClosed(code: Int, reason: String) {
                if (!isSessionEpochCurrent(epoch)) return
                Log.i(TAG, "onClosed: code=$code reason=$reason")
                shutdown(reason = if (code == 1000) null else "Voice session closed.")
            }
        }
    }

    /** Execute a native tool and send the result back to Gemini Live. */
    private fun dispatchNativeTool(callId: String, name: String, args: String, epoch: Long) {
        val toolName = name.trim()
        if (toolName.isBlank()) return

        if (!toolDispatcher.isSupported(toolName)) {
            Log.w(TAG, "unsupported tool: $toolName")
            runCatching {
                liveSession?.sendToolResponse(callId, toolName, "Unknown tool: $toolName")
            }
            return
        }

        scope.launch {
            if (!isSessionEpochCurrent(epoch)) return@launch
            toolCallsInFlight.incrementAndGet()
            HudStateBridge.update { it.copy(notification = "Running $toolName…") }
            try {
                val result = toolDispatcher.dispatch(toolName, args)
                val resultText = result.getOrElse { err ->
                    Log.w(TAG, "tool failed name=$toolName: ${err.message}")
                    err.message?.trim().takeUnless { it.isNullOrBlank() }
                        ?: "Tool $toolName is unavailable right now."
                }
                Log.i(TAG, "tool result name=$toolName text='${resultText.take(180)}'")
                if (!isSessionEpochCurrent(epoch)) return@launch
                val ok = runCatching {
                    liveSession?.sendToolResponse(callId, toolName, resultText) == true
                }.getOrDefault(false)
                Log.i(TAG, "sendToolResponse returned $ok name=$toolName callId=$callId")
                HudStateBridge.update { it.copy(notification = null) }
            } finally {
                toolCallsInFlight.decrementAndGet()
                noteConversationActivity()
            }
        }
    }

    /**
     * Mars's spec: Gemini ends the conversation after 5 seconds of
     * silence, timed whenever NEITHER side is speaking. "Activity" =
     * user speech on the mic (level gate in the read loop) or any
     * transcription event, model audio playing (write-time tracking in
     * GeminiAudioPlayer), or a tool call in flight. The countdown also
     * runs right after the session opens — connect it and say nothing
     * for 5s and it closes.
     */
    private fun startSilenceWatchdog(epoch: Long) {
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = scope.launch {
            while (isActive && isSessionEpochCurrent(epoch)) {
                delay(SILENCE_WATCHDOG_TICK_MS)
                if (!liveSessionReady || !isSessionEpochCurrent(epoch)) continue
                val now = SystemClock.uptimeMillis()
                val busy = toolCallsInFlight.get() > 0 ||
                    audioPlayer.isActivelySpeaking(windowMs = 600L)
                if (busy) {
                    lastConversationActivityMs = now
                    continue
                }
                val idleFor = now - lastConversationActivityMs
                if (idleFor >= SILENCE_END_MS) {
                    Log.i(TAG, "Silence watchdog: ${idleFor}ms of mutual silence — ending session")
                    shutdown(reason = null)
                    break
                }
            }
        }
    }

    private fun startAudioStreaming(epoch: Long) {
        if (captureActive) return
        if (!isSessionEpochCurrent(epoch)) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "startAudioStreaming: minBufferSize=$minBuffer")
            HudStateBridge.update {
                it.copy(notification = "Microphone buffer could not be created.")
            }
            return
        }
        val bufferSize = maxOf(minBuffer * 2, 4096)
        val recorder = createAudioRecord(bufferSize)
        if (recorder == null) {
            Log.w(TAG, "startAudioStreaming: AudioRecord init failed")
            HudStateBridge.update {
                it.copy(notification = "Microphone could not be opened.")
            }
            return
        }

        audioRecord = recorder
        captureActive = true
        runCatching { recorder.startRecording() }

        audioThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val chunk = ByteArray(2048)
            var loggedFirstFrame = false
            while (captureActive && isSessionEpochCurrent(epoch)) {
                val read = try {
                    recorder.read(chunk, 0, chunk.size)
                } catch (e: Throwable) {
                    Log.w(TAG, "audio read threw: ${e.message}")
                    break
                }
                if (read > 0) {
                    if (!loggedFirstFrame) {
                        loggedFirstFrame = true
                        Log.d(TAG, "First mic frame: $read bytes")
                    }
                    val peak = calculatePcm16Peak(chunk, read)
                    val norm = (peak / 32_767f).coerceIn(0f, 1f)
                    // A mic level clearly above ambient counts as the user
                    // speaking for the mutual-silence watchdog.
                    if (norm >= USER_SPEECH_LEVEL) {
                        lastConversationActivityMs = SystemClock.uptimeMillis()
                    }
                    // Only drive the USER (red) glow while LISTENING — the
                    // mic keeps streaming during Gemini's reply (barge-in),
                    // and the red level would clobber the green MODEL level.
                    if (HudStateBridge.current().phase ==
                            HudStateBridge.VoicePhase.LISTENING &&
                        (norm > 0.04f || (System.currentTimeMillis() % 8L == 0L))
                    ) {
                        HudStateBridge.update {
                            it.copy(
                                oscilloscopeLevel = norm,
                                oscilloscopeChannel = HudStateBridge.OscilloscopeChannel.USER
                            )
                        }
                    }
                    if (isSessionEpochCurrent(epoch)) {
                        runCatching {
                            liveSession?.sendAudioChunkPcm16(chunk, read, SAMPLE_RATE_HZ)
                        }
                    }
                } else if (read < 0) {
                    Log.w(TAG, "audio read error code=$read")
                }
            }
            Log.d(TAG, "Audio thread exiting")
        }, "GeminiVoicePipelineAudioThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val rec = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(
                    TAG,
                    "AudioRecord opened with source=" +
                        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) "VOICE_COMM" else "MIC"
                )
                return rec
            }
            runCatching { rec.release() }
        }
        return null
    }

    private fun calculatePcm16Peak(data: ByteArray, size: Int): Int {
        var peak = 0
        var i = 0
        val limit = size - 1
        while (i < limit) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt() shl 8
            val sample = (hi or lo).toShort().toInt()
            val abs = if (sample < 0) -sample else sample
            if (abs > peak) peak = abs
            i += 2
        }
        return peak
    }

    companion object {
        private const val TAG = "GeminiVoicePipe"
        private const val SAMPLE_RATE_HZ = 16_000

        /** Mars's spec: mutual silence that ends the conversation. */
        private const val SILENCE_END_MS = 5_000L
        private const val SILENCE_WATCHDOG_TICK_MS = 250L

        /** How long after a turn completes to drop duplicate late output
         *  transcription chunks. Short enough that a real follow-up is
         *  never suppressed. */
        private const val LATE_OUTPUT_DROP_MS = 500L

        /** How recently a camera frame must have arrived for the feed to
         *  count as live (frames stream ~1.1s apart). */
        private const val CAMERA_FRESH_WINDOW_MS = 4_000L

        /** Normalized mic peak treated as "the user is speaking" for the
         *  watchdog. High enough that room ambience doesn't hold the
         *  session open; near-mouth speech on the X3's array clears it. */
        private const val USER_SPEECH_LEVEL = 0.12f
    }
}
