package com.x3gemini.app.core.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HUD pin board — persistence + cross-module bridge for user-pinned
 * HUD content ("hud posts"): icons that open URLs, post-it notes that
 * link to Google Tasks, and pictures that open full screen.
 *
 * Written by BOTH modules in the same process:
 *   • visionclaw's HudPinTool (Gemini voice: "pin that to my HUD")
 *   • tapbrowser's HudPinBoardController (long-tap delete / move)
 * so it follows the ChatCardBridge singleton pattern: thread-safe
 * state, listeners fire on the mutating thread, UI consumers hop to
 * main themselves.
 *
 * Persistence: its OWN SharedPreferences file ("hud_pin_store"), NOT
 * visionclaw_prefs and NOT chat_context. Pins aren't companion-app
 * config and keeping them out of visionclaw_prefs avoids ever
 * colliding with CompanionServer's allowed_config_keys machinery
 * (lesson of the Oakland bug, commit 030d119: be deliberate about
 * which prefs file owns what).
 */
object HudPinStore {

    const val TYPE_ICON = "icon"
    const val TYPE_NOTE = "note"
    const val TYPE_PICTURE = "picture"

    /**
     * Live card — [payload] is a natural-language WATCH QUERY ("Warriors
     * score", "top AI headline", "new trending Rust repos", "changes to
     * <page>"), optionally scoped to [HudPin.sourceUrl]. The
     * LiveCardEngine (visionclaw side) refreshes [HudPin.content] every
     * [HudPin.intervalSec] and flips [HudPin.stale] on fetch failure;
     * the board renders content and dims stale cards.
     */
    const val TYPE_LIVE = "live"

    /** Hard cap — the pin zone is small (~150×90dp usable). */
    const val MAX_PINS = 10

    private const val PREFS_FILE = "hud_pin_store"
    private const val KEY_PINS = "hud_pins"

    /**
     * One HUD post.
     *
     * [payload] meaning by [type]:
     *   icon    → the URL the icon opens in TapBrowser
     *   note    → the note body text
     *   picture → absolute file path (screen grabs saved by HudPinTool)
     *             or an http(s) image URL
     * [linkUrl]: optional tap-through override. Notes default to
     * Google Tasks; icons default to [payload]; pictures open the
     * fullscreen viewer and ignore it.
     * [customX]/[customY]: overlay-space position in px once the user
     * has manually moved the pin; -1 = auto-grid slot.
     */
    data class HudPin(
        val id: String = UUID.randomUUID().toString(),
        val type: String,
        val label: String,
        val payload: String,
        val linkUrl: String? = null,
        val customX: Int = -1,
        val customY: Int = -1,
        val createdAt: Long = System.currentTimeMillis(),
        // ── live-card fields (TYPE_LIVE only; inert defaults otherwise) ──
        /** Optional URL to watch (a scoreboard, feed, repo list, any page). */
        val sourceUrl: String? = null,
        /** Latest engine-produced display text ("" until first refresh). */
        val content: String = "",
        /** Wall-clock ms of the last SUCCESSFUL refresh; 0 = never. */
        val updatedAt: Long = 0L,
        /** Refresh cadence in seconds; 0 = not a live pin. */
        val intervalSec: Int = 0,
        /** True when the last refresh attempt failed — UI dims the card. */
        val stale: Boolean = false,
        /**
         * Short human-readable status shown on the card when a refresh
         * isn't succeeding — e.g. "rate-limited", "no data", "error 500".
         * Runtime-only (deliberately NOT persisted, so a transient error
         * never survives a restart); cleared on the next success.
         */
        val statusNote: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("type", type)
            .put("label", label)
            .put("payload", payload)
            .put("linkUrl", linkUrl ?: JSONObject.NULL)
            .put("customX", customX)
            .put("customY", customY)
            .put("createdAt", createdAt)
            .put("sourceUrl", sourceUrl ?: JSONObject.NULL)
            .put("content", content)
            .put("updatedAt", updatedAt)
            .put("intervalSec", intervalSec)
            .put("stale", stale)

        companion object {
            fun fromJson(o: JSONObject): HudPin? {
                val type = o.optString("type").takeIf { it.isNotBlank() } ?: return null
                return HudPin(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    type = type,
                    label = o.optString("label"),
                    payload = o.optString("payload"),
                    linkUrl = o.optString("linkUrl").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    customX = o.optInt("customX", -1),
                    customY = o.optInt("customY", -1),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    sourceUrl = o.optString("sourceUrl").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    content = o.optString("content"),
                    updatedAt = o.optLong("updatedAt", 0L),
                    intervalSec = o.optInt("intervalSec", 0),
                    stale = o.optBoolean("stale", false)
                )
            }
        }
    }

    @SuppressLint("StaticFieldLeak") // application context only
    @Volatile private var appContext: Context? = null
    private val lock = Any()
    @Volatile private var cache: List<HudPin>? = null
    private val listeners = CopyOnWriteArrayList<(List<HudPin>) -> Unit>()

    /**
     * Idempotent. Either module may call first (tapbrowser
     * MainActivity.onCreate in practice; HudPinTool defensively).
     * Always stores the application context, never an Activity.
     */
    fun init(context: Context) {
        if (appContext == null) {
            synchronized(lock) {
                if (appContext == null) appContext = context.applicationContext
            }
        }
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun all(): List<HudPin> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val loaded = load()
            cache = loaded
            return loaded
        }
    }

    /**
     * Adds [pin]; returns false when at [MAX_PINS] capacity.
     *
     * Dedupe: re-pinning the SAME target (same type + payload, e.g.
     * asking Gemini to pin the current station twice, or the same
     * station with fresher metadata) REPLACES the existing pin in
     * place — keeping its id and any manual position — instead of
     * stacking an identical twin on the board.
     */
    fun add(pin: HudPin): Boolean {
        synchronized(lock) {
            val current = all()
            val existingIdx = current.indexOfFirst {
                it.type == pin.type && it.payload == pin.payload
            }
            if (existingIdx >= 0) {
                val existing = current[existingIdx]
                val next = current.toMutableList()
                next[existingIdx] = pin.copy(
                    id = existing.id,
                    customX = existing.customX,
                    customY = existing.customY,
                    createdAt = existing.createdAt
                )
                persist(next)
            } else {
                if (current.size >= MAX_PINS) return false
                persist(current + pin)
            }
        }
        notifyListeners()
        return true
    }

    /** Remove by exact id. Returns true when something was removed. */
    fun remove(id: String): Boolean {
        val removed: Boolean
        synchronized(lock) {
            val current = all()
            val next = current.filterNot { it.id == id }
            removed = next.size != current.size
            if (removed) persist(next)
        }
        if (removed) notifyListeners()
        return removed
    }

    /**
     * Remove by fuzzy label ("delete the cat pin" shouldn't require
     * the exact stored label). Case-insensitive containment either
     * direction; falls back to matching against note body text.
     * Returns the removed pin's label, or null when nothing matched.
     */
    fun removeByLabel(query: String): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        var removedLabel: String? = null
        synchronized(lock) {
            val current = all()
            val victim = current.firstOrNull {
                val l = it.label.trim().lowercase()
                l == q || l.contains(q) || q.contains(l) && l.isNotEmpty()
            } ?: current.firstOrNull {
                it.type == TYPE_NOTE && it.payload.lowercase().contains(q)
            } ?: return null
            removedLabel = victim.label
            persist(current.filterNot { it.id == victim.id })
        }
        notifyListeners()
        return removedLabel
    }

    /** Persist a manual move (overlay-space px). */
    fun updatePosition(id: String, x: Int, y: Int) {
        synchronized(lock) {
            val current = all()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return
            val next = current.toMutableList()
            next[idx] = next[idx].copy(customX = x, customY = y)
            persist(next)
        }
        notifyListeners()
    }

    /** Engine writes a live card's fresh display text (success path).
     *  Clears any stale flag and status note. */
    fun updateContent(id: String, content: String) {
        synchronized(lock) {
            val current = all()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return
            val next = current.toMutableList()
            next[idx] = next[idx].copy(
                content = content,
                updatedAt = System.currentTimeMillis(),
                stale = false,
                statusNote = null
            )
            persist(next)
        }
        notifyListeners()
    }

    /** Engine flags a live card whose refresh keeps failing (dead source).
     *  [note] is a short user-facing reason shown on the card. */
    fun markStale(id: String, note: String? = null) {
        synchronized(lock) {
            val current = all()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return
            val cur = current[idx]
            if (cur.stale && cur.statusNote == note) return
            val next = current.toMutableList()
            next[idx] = cur.copy(stale = true, statusNote = note)
            persist(next)
        }
        notifyListeners()
    }

    /**
     * Set a card's status note WITHOUT marking it stale — for conditions
     * that aren't a dead source, e.g. "rate-limited" (throttled but the
     * last value is still valid). Pass null to clear.
     */
    fun setStatus(id: String, note: String?) {
        synchronized(lock) {
            val current = all()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0 || current[idx].statusNote == note) return
            val next = current.toMutableList()
            next[idx] = next[idx].copy(statusNote = note)
            persist(next)
        }
        notifyListeners()
    }

    fun clear() {
        synchronized(lock) { persist(emptyList()) }
        notifyListeners()
    }

    // ── Refresh-request bus (UI → engine, same-process) ──────────────

    private val refreshListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** LiveCardEngine subscribes; fires with the pin id to refresh NOW. */
    fun onRefreshRequest(listener: (String) -> Unit): AutoCloseable {
        refreshListeners.add(listener)
        return AutoCloseable { refreshListeners.remove(listener) }
    }

    /** UI asks for an immediate refresh (tap on a live card). */
    fun requestRefresh(id: String) {
        for (l in refreshListeners) {
            try {
                l(id)
            } catch (_: Throwable) {
                // never let a consumer crash the publisher
            }
        }
    }

    /**
     * Subscribe; fires once synchronously with current state, then on
     * every mutation. Returns an [AutoCloseable] for lifecycle-tied
     * removal (same contract as ChatCardBridge.observe).
     */
    fun observe(listener: (List<HudPin>) -> Unit): AutoCloseable {
        listeners.add(listener)
        try {
            listener(all())
        } catch (_: Throwable) {
            // never let a buggy listener escape
        }
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val snapshot = all()
        for (l in listeners) {
            try {
                l(snapshot)
            } catch (_: Throwable) {
                // ditto
            }
        }
    }

    private fun persist(pins: List<HudPin>) {
        cache = pins
        val arr = JSONArray()
        pins.forEach { arr.put(it.toJson()) }
        prefs()?.edit()?.putString(KEY_PINS, arr.toString())?.apply()
    }

    private fun load(): List<HudPin> {
        val raw = prefs()?.getString(KEY_PINS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { HudPin.fromJson(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
