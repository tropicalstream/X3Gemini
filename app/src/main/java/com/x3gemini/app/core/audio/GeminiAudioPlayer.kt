package com.x3gemini.app.core.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * Streams Gemini Live PCM chunks directly to device audio using AudioTrack.
 */
class GeminiAudioPlayer(context: Context) {

    /** Callback invoked on a background thread once the AudioTrack buffer has drained. */
    var onDrainComplete: (() -> Unit)? = null

    companion object {
        private const val TAG = "GeminiAudioPlayer"
        private const val DEFAULT_SAMPLE_RATE = 24_000
        /**
         * Slice size used by [playChunk] when feeding the AudioTrack. ~16 KB
         * is ~170 ms at 24 kHz mono PCM16 — small enough that stopAndFlush()
         * can break the write loop within ~200 ms, large enough to avoid
         * per-slice overhead.
         */
        private const val SLICE_BYTES = 16 * 1024
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0
    private var loggedPlaybackStart = false
    private var hasAudioFocus = false
    private var focusRequest: AudioFocusRequest? = null
    /** Set to true when the server turn is complete; the drain thread watches this. */
    @Volatile private var drainPending = false
    private var drainThread: Thread? = null
    @Volatile private var lastOutputLevel = 0f
    @Volatile private var lastOutputAtMs = 0L
    /**
     * Monotonically increasing "write generation". Every call to stopAndFlush()
     * or release() bumps this. A playChunk loop that sees its generation is
     * stale aborts immediately, so the UI thread's stopAndFlush() can
     * reliably cut short a long blocking write without waiting for AudioTrack
     * internals to return from write().
     */
    @Volatile private var writeGeneration = 0L

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(lock) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Focus came back — restore full volume and resume if a
                    // transient loss had paused us.
                    hasAudioFocus = true
                    runCatching { audioTrack?.setVolume(1f) }
                    runCatching {
                        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) {
                            audioTrack?.play()
                        }
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // PERMANENT loss (a phone call, another app taking over for
                    // good). Stop and release.
                    hasAudioFocus = false
                    runCatching { audioTrack?.pause() }
                }
                else -> {
                    // Transient / duck loss (change == -2 or -3) — e.g. a PAUSED
                    // YouTube video in the WebView momentarily re-asserting audio
                    // focus. The old code did `change <= AUDIOFOCUS_LOSS`, which
                    // is true for the transient values too (they are MORE
                    // negative than AUDIOFOCUS_LOSS), so it paused Gemini and
                    // never resumed — freezing speech mid-sentence. The
                    // assistant's voice is the primary interaction, so we keep
                    // speaking through transient losses. (We briefly duck; the
                    // next audio chunk resets volume to 1f, so it self-heals.)
                    runCatching { audioTrack?.setVolume(0.4f) }
                }
            }
        }
    }

    fun playChunk(
        mimeType: String,
        data: ByteArray,
        muted: Boolean,
        volume: Float
    ) {
        if (data.isEmpty() || muted) return
        val sampleRate = parseSampleRate(mimeType) ?: DEFAULT_SAMPLE_RATE

        // Acquire / configure the track under the lock, then RELEASE the lock
        // before writing. Holding the lock across the write loop would
        // deadlock stopAndFlush() (and anything else that grabs this lock)
        // for the entire playback duration — which can be several minutes
        // for a research report readout.
        var track: AudioTrack
        val myGeneration: Long
        synchronized(lock) {
            requestAudioFocusLocked()
            track = ensureTrackLocked(sampleRate) ?: return
            val safeVolume = volume.coerceIn(0f, 1f)
            runCatching { track.setVolume(safeVolume) }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { track.play() }
            }
            myGeneration = writeGeneration
        }

        // Write in small NON-BLOCKING slices so we can observe stopAndFlush()
        // (which bumps writeGeneration) and bail out immediately. A single
        // WRITE_BLOCKING call on several MB of PCM would otherwise keep this
        // thread inside AudioTrack.write() for the entire playback, and
        // AudioTrack.pause()/stop() is not guaranteed to interrupt it.
        val sliceBytes = SLICE_BYTES
        var offset = 0
        var totalWritten = 0
        var aborted = false
        // Cap the number of dead-track recoveries within a single chunk so a
        // genuinely broken audio HAL can't trap us in an infinite rebuild
        // loop. Three is plenty — the only realistic source of repeated
        // DEAD_OBJECT in one chunk is a flapping audio route, which we'd
        // rather give up on than burn CPU pretending to recover.
        var deadObjectRecoveries = 0
        val maxDeadObjectRecoveries = 3
        while (offset < data.size) {
            // Cancellation check — cheap, just a volatile read.
            if (writeGeneration != myGeneration) {
                aborted = true
                break
            }
            val remaining = data.size - offset
            val toWrite = if (remaining < sliceBytes) remaining else sliceBytes
            val wrote = runCatching {
                track.write(data, offset, toWrite, AudioTrack.WRITE_NON_BLOCKING)
            }.getOrElse { -1 }

            if (wrote == AudioTrack.ERROR_DEAD_OBJECT) {
                // The system tore down our AudioTrack underneath us. Common
                // triggers: an external process (scrcpy --audio-source=output,
                // a screen recorder, a BT-headset pairing flip) grabbed the
                // audio HAL via MediaProjection; a system event re-routed
                // audio output mid-stream; the audio service itself was
                // restarted. Android's framework auto-creates a replacement
                // track but our reference is now writing into a dead object,
                // which previously left playback silently aborted (mid-word
                // cut-off, chat-card frozen because the local transcription
                // is driven by AudioTrack progression).
                //
                // Recovery: release our dead reference, build a new track
                // with the same params, replay this slice on it. The
                // generation counter is preserved so a concurrent
                // stopAndFlush() still aborts us cleanly.
                deadObjectRecoveries++
                if (deadObjectRecoveries > maxDeadObjectRecoveries) {
                    Log.w(
                        TAG,
                        "AudioTrack DEAD_OBJECT — recovery cap reached " +
                            "($maxDeadObjectRecoveries attempts), dropping remainder"
                    )
                    aborted = true
                    break
                }
                val replacement = synchronized(lock) {
                    runCatching { audioTrack?.release() }
                    audioTrack = null
                    trackSampleRate = 0
                    val newTrack = ensureTrackLocked(sampleRate)
                    if (newTrack != null) {
                        val safeVolume = volume.coerceIn(0f, 1f)
                        runCatching { newTrack.setVolume(safeVolume) }
                        if (newTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            runCatching { newTrack.play() }
                        }
                    }
                    newTrack
                }
                if (replacement == null) {
                    Log.w(
                        TAG,
                        "AudioTrack DEAD_OBJECT — could not rebuild, dropping remainder"
                    )
                    aborted = true
                    break
                }
                Log.w(
                    TAG,
                    "AudioTrack DEAD_OBJECT recovered " +
                        "(attempt $deadObjectRecoveries) — resuming at offset=$offset"
                )
                track = replacement
                // Reset offset is intentionally NOT done — the dead track
                // already absorbed earlier slices for this chunk; resuming
                // from the current offset just continues the utterance with
                // a tiny hiccup of dropped frames at the failure point. A
                // full restart from offset=0 would re-speak audio the user
                // already heard.
                continue
            }

            if (wrote < 0) {
                // Other negative return codes (ERROR_INVALID_OPERATION /
                // ERROR_BAD_VALUE / ERROR / etc.) — track stopped, released,
                // or in a state that's not recoverable here. Exit.
                break
            }
            if (wrote == 0) {
                // Buffer full — sleep briefly so the device can consume audio.
                // This tiny sleep is interruptible and keeps us responsive to
                // generation changes.
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    aborted = true
                    break
                }
                continue
            }
            offset += wrote
            totalWritten += wrote
        }

        synchronized(lock) {
            lastOutputLevel = calculatePcm16Level(data)
            lastOutputAtMs = SystemClock.uptimeMillis()
            if (!loggedPlaybackStart && totalWritten > 0) {
                loggedPlaybackStart = true
                Log.d(
                    TAG,
                    "Streaming Gemini audio sampleRate=$sampleRate bytes=$totalWritten (aborted=$aborted)"
                )
            }
        }
    }

    /**
     * Called when the server signals turn-complete.  Starts a background thread
     * that waits for the AudioTrack to finish playing all buffered audio, then
     * invokes [onDrainComplete] so the caller can arm the silence watchdog only
     * after the user has heard every word.
     */
    fun notifyTurnComplete() {
        drainPending = true
        if (drainThread?.isAlive == true) return  // already watching
        drainThread = Thread({
            try {
                // Poll AudioTrack head position until it stops advancing,
                // meaning all buffered data has been played.
                var stallCount = 0
                var lastHead = -1
                while (drainPending) {
                    val head = synchronized(lock) {
                        audioTrack?.playbackHeadPosition ?: -1
                    }
                    if (head < 0) break   // track gone
                    if (head == lastHead) {
                        stallCount++
                        // 6 × 50ms = 300ms of no advancement → drained
                        if (stallCount >= 6) break
                    } else {
                        stallCount = 0
                        lastHead = head
                    }
                    Thread.sleep(50)
                }
            } catch (_: InterruptedException) { }
            drainPending = false
            Log.d(TAG, "Audio drain complete — invoking callback")
            onDrainComplete?.invoke()
        }, "GeminiAudioDrain").also { it.isDaemon = true; it.start() }
    }

    /** Cancel any pending drain watcher (e.g. when a new turn starts). */
    fun cancelDrain() {
        drainPending = false
    }

    fun currentOutputLevel(): Float = lastOutputLevel

    fun isActivelySpeaking(windowMs: Long = 350L): Boolean {
        return SystemClock.uptimeMillis() - lastOutputAtMs <= windowMs && lastOutputLevel > 0.01f
    }

    fun stopAndFlush() {
        drainPending = false
        // Bump the generation OUTSIDE the lock so any playChunk loop still
        // holding a generation snapshot will see it change and exit on its
        // next slice check, without waiting for the lock.
        writeGeneration++
        synchronized(lock) {
            val track = audioTrack ?: return
            runCatching { track.pause() }
            runCatching { track.flush() }
            loggedPlaybackStart = false
            lastOutputLevel = 0f
            abandonAudioFocusLocked()
        }
    }

    fun release() {
        drainPending = false
        writeGeneration++
        synchronized(lock) {
            val track = audioTrack
            audioTrack = null
            trackSampleRate = 0
            loggedPlaybackStart = false
            lastOutputLevel = 0f
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.release() }
            abandonAudioFocusLocked()
        }
    }

    private fun ensureTrackLocked(sampleRate: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null &&
            trackSampleRate == sampleRate &&
            existing.state == AudioTrack.STATE_INITIALIZED
        ) {
            return existing
        }

        runCatching {
            existing?.pause()
            existing?.flush()
            existing?.release()
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "Invalid AudioTrack minBufferSize: $minBuffer")
            audioTrack = null
            trackSampleRate = 0
            return null
        }

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuffer * 2, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "Failed to initialize AudioTrack for sampleRate=$sampleRate")
            runCatching { track?.release() }
            audioTrack = null
            trackSampleRate = 0
            loggedPlaybackStart = false
            return null
        }

        audioTrack = track
        trackSampleRate = sampleRate
        loggedPlaybackStart = false
        return track
    }

    private fun parseSampleRate(mimeType: String): Int? {
        val match = Regex("""rate=(\d+)""").find(mimeType)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun calculatePcm16Level(data: ByteArray): Float {
        if (data.size < 2) return 0f
        var peak = 0
        var i = 0
        while (i + 1 < data.size) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            peak = max(peak, abs(sample))
            i += 2
        }
        return (peak / 32767f).coerceIn(0f, 1f)
    }

    private fun requestAudioFocusLocked() {
        if (hasAudioFocus) return
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req =
                    focusRequest
                        ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setOnAudioFocusChangeListener(focusChangeListener)
                            .build()
                            .also { focusRequest = it }
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) {
            Log.w(TAG, "Audio focus not granted for Gemini playback")
        }
    }

    private fun abandonAudioFocusLocked() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasAudioFocus = false
    }
}
