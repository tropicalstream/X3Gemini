package com.x3gemini.app.core.config

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Persistent assistant personalization — the three stores behind the
 * "real assistant" feature set (memory, custom instructions, custom
 * commands). Reminders live in ReminderStore (they carry alarms).
 *
 * Everything here is injected into the Live session's system prompt at
 * setup ([promptSection]), which is what makes memory survive across
 * sessions: the Live API itself is stateless per-connection.
 *
 * Own SharedPreferences file ("assistant_store") per the HudPinStore
 * lesson: be deliberate about which prefs file owns what.
 */
object AssistantStore {

    private const val TAG = "AssistantStore"
    private const val PREFS_FILE = "assistant_store"
    private const val KEY_MEMORIES = "memories"
    private const val KEY_INSTRUCTIONS = "custom_instructions"
    private const val KEY_COMMANDS = "custom_commands"

    /** Caps keep the injected prompt bounded (~2k tokens worst case). */
    const val MAX_MEMORIES = 60
    const val MAX_MEMORY_CHARS = 200
    const val MAX_INSTRUCTIONS_CHARS = 1500
    const val MAX_COMMANDS = 20
    const val MAX_COMMAND_CHARS = 1200

    data class Memory(val id: String, val text: String, val createdAt: Long)

    @SuppressLint("StaticFieldLeak")
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun p(): SharedPreferences =
        prefs ?: throw IllegalStateException("AssistantStore.init() not called")

    // ── Memory ─────────────────────────────────────────────────────

    fun memories(): List<Memory> {
        val raw = p().getString(KEY_MEMORIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    Memory(
                        id = it.optString("id"),
                        text = it.optString("text"),
                        createdAt = it.optLong("createdAt")
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Appends a memory; drops the oldest beyond [MAX_MEMORIES]. */
    fun remember(text: String): Boolean {
        val clean = text.trim().take(MAX_MEMORY_CHARS)
        if (clean.isBlank()) return false
        val current = memories().toMutableList()
        // Dedup: an identical (case-insensitive) memory is a no-op success.
        if (current.any { it.text.equals(clean, ignoreCase = true) }) return true
        current.add(Memory(UUID.randomUUID().toString(), clean, System.currentTimeMillis()))
        while (current.size > MAX_MEMORIES) current.removeAt(0)
        save(current)
        return true
    }

    /** Removes the best-matching memory; returns its text, or null. */
    fun forget(query: String): String? {
        val q = query.trim().lowercase(Locale.US)
        if (q.isBlank()) return null
        val current = memories().toMutableList()
        val hit = current.firstOrNull { it.text.lowercase(Locale.US).contains(q) }
            ?: return null
        current.remove(hit)
        save(current)
        return hit.text
    }

    fun clearMemories() = save(emptyList())

    private fun save(list: List<Memory>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("text", it.text)
                    .put("createdAt", it.createdAt)
            )
        }
        p().edit().putString(KEY_MEMORIES, arr.toString()).apply()
    }

    // ── Custom instructions ───────────────────────────────────────

    fun instructions(): String? =
        p().getString(KEY_INSTRUCTIONS, null)?.trim()?.takeIf { it.isNotBlank() }

    fun setInstructions(text: String?) {
        val clean = text?.trim()?.take(MAX_INSTRUCTIONS_CHARS)
        p().edit().apply {
            if (clean.isNullOrBlank()) remove(KEY_INSTRUCTIONS)
            else putString(KEY_INSTRUCTIONS, clean)
        }.apply()
        Log.i(TAG, "custom instructions ${if (clean.isNullOrBlank()) "cleared" else "set (${clean.length} chars)"}")
    }

    // ── Custom commands ───────────────────────────────────────────

    fun commands(): Map<String, String> {
        val raw = p().getString(KEY_COMMANDS, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = linkedMapOf<String, String>()
            for (k in obj.keys()) out[k] = obj.optString(k)
            out
        }.getOrDefault(emptyMap())
    }

    /** Case-insensitive, fuzzy: exact name, else contains-match. */
    fun command(name: String): Pair<String, String>? {
        val q = name.trim().lowercase(Locale.US)
        if (q.isBlank()) return null
        val all = commands()
        all.entries.firstOrNull { it.key.lowercase(Locale.US) == q }
            ?.let { return it.key to it.value }
        return all.entries.firstOrNull {
            it.key.lowercase(Locale.US).contains(q) || q.contains(it.key.lowercase(Locale.US))
        }?.let { it.key to it.value }
    }

    fun saveCommand(name: String, promptText: String): Boolean {
        val cleanName = name.trim().take(40)
        val cleanPrompt = promptText.trim().take(MAX_COMMAND_CHARS)
        if (cleanName.isBlank() || cleanPrompt.isBlank()) return false
        val all = commands().toMutableMap()
        // Replacing an existing command is always allowed; only NEW names count
        // against the cap.
        if (!all.keys.any { it.equals(cleanName, ignoreCase = true) } &&
            all.size >= MAX_COMMANDS
        ) return false
        all.keys.firstOrNull { it.equals(cleanName, ignoreCase = true) }?.let { all.remove(it) }
        all[cleanName] = cleanPrompt
        val obj = JSONObject()
        all.forEach { (k, v) -> obj.put(k, v) }
        p().edit().putString(KEY_COMMANDS, obj.toString()).apply()
        return true
    }

    fun deleteCommand(name: String): String? {
        val all = commands().toMutableMap()
        val hit = all.keys.firstOrNull { it.equals(name.trim(), ignoreCase = true) }
            ?: all.keys.firstOrNull { it.lowercase(Locale.US).contains(name.trim().lowercase(Locale.US)) }
            ?: return null
        all.remove(hit)
        val obj = JSONObject()
        all.forEach { (k, v) -> obj.put(k, v) }
        p().edit().putString(KEY_COMMANDS, obj.toString()).apply()
        return hit
    }

    // ── System prompt injection ───────────────────────────────────

    /**
     * The personalization block appended to the Live system prompt at
     * session setup. Empty string when nothing is stored.
     */
    fun promptSection(): String = buildString {
        instructions()?.let {
            append("\n\nCUSTOM INSTRUCTIONS FROM THE USER (persistent personalization — ")
            append("follow them in every reply):\n")
            append(it)
        }
        val mems = memories()
        if (mems.isNotEmpty()) {
            append("\n\nMEMORY — durable facts the user asked you to keep. Use them ")
            append("naturally when relevant; never recite the whole list unasked:\n")
            mems.forEach { append("- ").append(it.text).append('\n') }
        }
        val cmds = commands()
        if (cmds.isNotEmpty()) {
            append("\nSAVED CUSTOM COMMANDS — when the user names one ('run my ")
            append("<name>', 'do my <name>'), call custom_command action=run with ")
            append("that name, then fully carry out the prompt it returns:\n")
            cmds.keys.forEach { append("- \"").append(it).append("\"\n") }
        }
    }
}

/**
 * adb path for long custom-instruction text (voice works for short ones):
 *
 *   adb shell am broadcast -a com.x3gemini.app.SET_INSTRUCTIONS \
 *     --es text "Always answer in a pirate voice. My name is Mars."
 *
 * An empty/missing extra clears the stored instructions.
 */
class InstructionsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AssistantStore.init(context)
        val text = intent.getStringExtra("text")
        AssistantStore.setInstructions(text)
        Log.i("AssistantStore", "SET_INSTRUCTIONS broadcast handled")
    }
}
