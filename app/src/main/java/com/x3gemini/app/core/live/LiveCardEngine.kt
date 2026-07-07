package com.x3gemini.app.core.live

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.x3gemini.app.core.bridge.HudPinStore
import com.x3gemini.app.core.bridge.HudPinStore.HudPin
import com.x3gemini.app.core.config.ApiKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * LiveCardEngine — refreshes TYPE_LIVE HUD pins on their per-pin
 * interval. DELIBERATELY source-agnostic: there are no per-site
 * parsers and no whitelist of "supported" sources. Every card is the
 * same two-step pipeline:
 *
 *   1. If the pin has a sourceUrl — fetch the RAW page (scoreboard,
 *      news site, GitHub repo list, any URL) and strip it to text.
 *   2. Hand the watch query (+ fetched text, or Google-Search
 *      grounding when there's no URL, + the previous card text for
 *      change detection) to gemini-2.5-flash, which returns the tiny
 *      display text for the card.
 *
 * That's what makes "news, particular headlines, new github
 * repositories, scores, changes to a web page etc" all work through
 * one code path: the model IS the parser.
 *
 * Staleness contract (HudPinStore): success → updateContent (resets
 * stale, stamps updatedAt); failure or model-reported UNAVAILABLE →
 * markStale, previous content stays visible and the board dims it.
 *
 * Runs process-wide from X3GeminiApp.onCreate. One worker thread,
 * one fetch in flight at a time — this is a pair of glasses, not a
 * server. Failed pins retry with a doubled interval so a dead source
 * can't burn battery. Tap-to-refresh arrives over
 * HudPinStore.onRefreshRequest and jumps the queue.
 */
object LiveCardEngine {

    private const val TAG = "LiveCardEngine"
    private const val MODEL = "gemini-2.5-flash"

    /** Max lines a live card renders / the model is allowed to return. */
    const val MAX_CARD_LINES = 12
    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

    /** Engine tick — how often due-ness is evaluated, NOT a refresh rate. */
    private const val TICK_SECONDS = 20L
    /** Floor for per-pin refresh intervals. */
    private const val MIN_INTERVAL_SEC = 60

    /** Consecutive failed refreshes before a card is shown as stale.
     *  Tolerates transient grounding / UNAVAILABLE blips. */
    private const val STALE_AFTER_FAILURES = 3

    /** Rate-limit (429) cooldown: escalates 1 min → capped at 15 min so a
     *  throttled card stops burning the exhausted quota. */
    private const val RATE_LIMIT_BASE_COOLDOWN_MS = 60_000L
    private const val RATE_LIMIT_MAX_COOLDOWN_MS = 15L * 60_000L
    /** Page fetch limits. */
    private const val FETCH_TIMEOUT_MS = 8_000
    private const val FETCH_MAX_BYTES = 96 * 1024
    private const val PAGE_TEXT_CAP = 12_000

    private const val SYSTEM_INSTRUCTION =
        "You refresh ONE compact live info card on an AR-glasses heads-up display. " +
            "Output ONLY the card text: up to $MAX_CARD_LINES lines, one item per line, " +
            "at most ~42 characters per line, plain text, no markdown, no quotes, no " +
            "labels, no preamble. Give as many relevant items as the user asked for " +
            "(e.g. 'top 4 headlines' = 4 lines); when no count is implied, a few lines " +
            "is plenty — never pad. Prefer hard facts — scores, numbers, headlines, " +
            "names — over prose. If a previous card text is provided and the user is " +
            "watching for changes, lead with what changed. If the information cannot be " +
            "determined from what you're given, output exactly: UNAVAILABLE"

    @SuppressLint("StaticFieldLeak") // application context only
    @Volatile private var appContext: Context? = null
    @Volatile private var executor: ScheduledThreadPoolExecutor? = null
    private val forced = Collections.synchronizedSet(mutableSetOf<String>())
    private val lastAttemptMs = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val failStreak = Collections.synchronizedMap(mutableMapOf<String, Int>())
    // Rate-limit (HTTP 429) backoff, kept separate from failStreak: a 429
    // is a quota/throttle condition, NOT a dead source, so it never marks
    // the card stale-red — it shows "rate-limited" and backs off.
    private val rateLimitStreak = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val rateLimitedUntilMs = Collections.synchronizedMap(mutableMapOf<String, Long>())

    /** Thrown by [callGemini] on a non-2xx response, carrying the code. */
    private class GeminiHttpException(val code: Int) : RuntimeException("Gemini API $code")

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Idempotent. Called from VisionClawApp.onCreate (and defensively
     *  from HudPinTool.add_live). */
    fun ensureStarted(context: Context) {
        if (executor != null) return
        synchronized(this) {
            if (executor != null) return
            appContext = context.applicationContext
            HudPinStore.init(context)
            HudPinStore.onRefreshRequest { id ->
                forced.add(id)
                executor?.execute { tick() }
            }
            executor = ScheduledThreadPoolExecutor(1).also {
                it.scheduleWithFixedDelay(::safeTick, 10, TICK_SECONDS, TimeUnit.SECONDS)
            }
            Log.d(TAG, "started")
        }
    }

    private fun safeTick() {
        try {
            tick()
        } catch (t: Throwable) {
            Log.w(TAG, "tick failed: ${t.message}")
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val livePins = HudPinStore.all().filter {
            it.type == HudPinStore.TYPE_LIVE && it.intervalSec > 0
        }
        if (livePins.isEmpty()) return
        for (pin in livePins) {
            if (isDue(pin, now)) {
                forced.remove(pin.id)
                refreshPin(pin)
            }
        }
        // GC bookkeeping for deleted pins
        val ids = livePins.map { it.id }.toSet()
        lastAttemptMs.keys.retainAll(ids)
        failStreak.keys.retainAll(ids)
        rateLimitStreak.keys.retainAll(ids)
        rateLimitedUntilMs.keys.retainAll(ids)
    }

    private fun isDue(pin: HudPin, now: Long): Boolean {
        if (forced.contains(pin.id)) return true
        // Honour a rate-limit cooldown: retrying while throttled just burns
        // more of the (already exhausted) quota and delays recovery.
        val cooldownUntil = rateLimitedUntilMs[pin.id] ?: 0L
        if (now < cooldownUntil) return false
        val streak = failStreak[pin.id] ?: 0
        val anchor = maxOf(pin.updatedAt, lastAttemptMs[pin.id] ?: 0L)
        // A card that has never loaded retries at the fast tick cadence
        // (not the 1-min floor) until it gets its first content — so a
        // transient blip on the very first fetch fills in within ~20s
        // instead of leaving the card on 'updating…'. Once it has failed
        // enough to be stale, fall through to the normal backed-off cadence.
        if (pin.updatedAt == 0L && streak < STALE_AFTER_FAILURES) {
            return now - anchor >= TICK_SECONDS * 1000L
        }
        val interval = maxOf(pin.intervalSec, MIN_INTERVAL_SEC) * 1000L
        // Don't slow the retry cadence for the first few transient misses —
        // that's what lets a card recover on the very next tick instead of
        // lingering. Only once a card is genuinely stale do we back off to
        // spare battery + API quota on a dead source.
        val backoffSteps = (streak - STALE_AFTER_FAILURES + 1).coerceIn(0, 3)
        val effective = interval * (1 shl backoffSteps)
        return now - anchor >= effective
    }

    private fun refreshPin(pin: HudPin) {
        val context = appContext ?: return
        lastAttemptMs[pin.id] = System.currentTimeMillis()
        try {
            val apiKey = ApiKeyStore.resolve(context)
                ?: throw IllegalStateException("no Gemini API key")

            val pageText = pin.sourceUrl?.let { fetchPageText(it) }
            Log.d(
                TAG,
                "refreshing '${pin.label}' model=$MODEL grounded=${pin.sourceUrl == null} " +
                    "query='${pin.payload.take(80)}'"
            )
            val text = callGemini(apiKey, pin, pageText)
            if (text.equals("UNAVAILABLE", ignoreCase = true)) {
                noteFailure(pin, "model returned UNAVAILABLE", userNote = "no data")
            } else {
                failStreak.remove(pin.id)
                rateLimitStreak.remove(pin.id)
                rateLimitedUntilMs.remove(pin.id)
                HudPinStore.updateContent(pin.id, clampCardText(text))
                Log.d(TAG, "'${pin.label}' refreshed ok (${text.length} chars)")
            }
        } catch (t: Throwable) {
            if ((t as? GeminiHttpException)?.code == 429) {
                noteRateLimited(pin)
            } else {
                val code = (t as? GeminiHttpException)?.code ?: -1
                noteFailure(pin, t.message ?: "refresh failed", userNote = userNoteForError(code))
            }
        }
    }

    /**
     * HTTP 429 = the Gemini free-tier request quota is exhausted. This is
     * not a dead source, so we DON'T mark the card stale-red — we surface
     * "rate-limited" on the card (so the user knows why it isn't updating),
     * keep the last value, and back off with an escalating cooldown
     * (1 min → capped at 15 min) so we stop burning quota. Recovers on the
     * next successful refresh once quota frees up.
     */
    private fun noteRateLimited(pin: HudPin) {
        val streak = (rateLimitStreak[pin.id] ?: 0) + 1
        rateLimitStreak[pin.id] = streak
        val cooldown = (RATE_LIMIT_BASE_COOLDOWN_MS shl minOf(streak - 1, 4))
            .coerceAtMost(RATE_LIMIT_MAX_COOLDOWN_MS)
        rateLimitedUntilMs[pin.id] = System.currentTimeMillis() + cooldown
        HudPinStore.setStatus(pin.id, "rate-limited")
        Log.w(
            TAG,
            "'${pin.label}' rate-limited (429) — backing off ${cooldown / 1000}s (streak $streak)"
        )
    }

    /**
     * A single failed refresh does NOT immediately stale the card.
     * Transient misses are common (a momentary grounding blip, a one-off
     * UNAVAILABLE), so keep showing the last good content and only flip to
     * the dimmed red 'stale' state (with [userNote] as the reason) after
     * [STALE_AFTER_FAILURES] consecutive misses. The retry stays at the
     * normal cadence until then (see [isDue]).
     */
    private fun noteFailure(pin: HudPin, reason: String, userNote: String) {
        val streak = (failStreak[pin.id] ?: 0) + 1
        failStreak[pin.id] = streak
        if (streak >= STALE_AFTER_FAILURES) {
            HudPinStore.markStale(pin.id, userNote)
            Log.w(TAG, "'${pin.label}' stale after $streak consecutive misses ($reason)")
        } else {
            Log.d(
                TAG,
                "'${pin.label}' transient miss $streak/$STALE_AFTER_FAILURES " +
                    "($reason) — keeping last content"
            )
        }
    }

    private fun userNoteForError(code: Int): String = when {
        code in 500..599 -> "server error"
        code == 403 -> "auth error"
        code > 0 -> "error $code"
        else -> "no connection"
    }

    // ── Step 1: raw source fetch ──────────────────────────────────────

    /** Fetch any URL and reduce it to plain text. No per-site logic. */
    private fun fetchPageText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = FETCH_TIMEOUT_MS
        conn.readTimeout = FETCH_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) X3Gemini")
        conn.setRequestProperty("Accept", "text/html,application/json,text/plain,*/*")
        try {
            val raw = conn.inputStream.use { it.readNBytesCompat(FETCH_MAX_BYTES) }
                .toString(Charsets.UTF_8)
            return raw
                .replace(Regex("(?is)<script.*?</script>"), " ")
                .replace(Regex("(?is)<style.*?</style>"), " ")
                .replace(Regex("(?is)<!--.*?-->"), " ")
                .replace(Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6])[^>]*>"), "\n")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#39;", "'").replace("&quot;", "\"")
                .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
                .replace(Regex("\\n\\s*\\n+"), "\n")
                .trim()
                .take(PAGE_TEXT_CAP)
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = read(chunk, 0, minOf(chunk.size, limit - total))
            if (n < 0) break
            buf.write(chunk, 0, n)
            total += n
        }
        return buf.toByteArray()
    }

    // ── Step 2: model distillation ────────────────────────────────────

    private fun callGemini(apiKey: String, pin: HudPin, pageText: String?): String {
        val user = buildString {
            append("Card label: ").append(pin.label).append('\n')
            append("Watch query: ").append(pin.payload).append('\n')
            if (pin.content.isNotBlank()) {
                append("Previous card text (for change detection):\n")
                append(pin.content).append('\n')
            }
            if (pageText != null) {
                append("Source page text, fetched just now from ")
                append(pin.sourceUrl).append(":\n")
                append(pageText).append('\n')
            } else {
                append("No source page — find the CURRENT answer with web search.\n")
            }
            append("Produce the updated card text now.")
        }

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION))
            ))
            put("contents", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", user)))
            ))
            // Google-Search grounding ONLY when we have no source page:
            // grounded requests cost more and a fetched page is already
            // fresher than the search index for watched-URL cards.
            if (pageText == null) {
                put("tools", JSONArray().put(
                    JSONObject().put("google_search", JSONObject())
                ))
            }
            put("generationConfig", JSONObject()
                .put("maxOutputTokens", 2048)
                .put("temperature", 0.2)
            )
        }

        val request = Request.Builder()
            .url("$API_BASE/$MODEL:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string().orEmpty()
            // Log the raw body on every response (truncated). This is the
            // single most useful diagnostic for a stale card: it shows a
            // non-200 error verbatim, a safety/grounding block with no
            // candidates, or a 200 whose text is "UNAVAILABLE".
            Log.d(TAG, "'${pin.label}' HTTP ${resp.code} body=${bodyText.take(400)}")
            if (!resp.isSuccessful) {
                Log.w(TAG, "Gemini ${resp.code}: ${bodyText.take(400)}")
                throw GeminiHttpException(resp.code)
            }
            return parseAnswer(bodyText)
        }
    }

    private fun parseAnswer(jsonText: String): String {
        val root = JSONObject(jsonText)
        val candidates = root.optJSONArray("candidates")
            ?: throw RuntimeException("no candidates")
        if (candidates.length() == 0) throw RuntimeException("empty candidates")
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            val text = parts.optJSONObject(i)?.optString("text")?.trim().orEmpty()
            if (text.isNotBlank()) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(text)
            }
        }
        val out = builder.toString().trim()
        if (out.isBlank()) throw RuntimeException("empty answer")
        return out
    }

    /** Belt-and-braces enforcement of the card contract. */
    private fun clampCardText(text: String): String =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_CARD_LINES)
            .joinToString("\n") { it.take(48) }
}
