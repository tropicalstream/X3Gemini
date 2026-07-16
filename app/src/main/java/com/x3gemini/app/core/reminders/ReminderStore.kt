package com.x3gemini.app.core.reminders

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persisted reminders ("remind me to take out the trash at 8pm").
 * Truth lives here; AlarmManager registrations are derived from it —
 * that's what lets ReminderReceiver rebuild every alarm after a reboot
 * (alarms don't survive reboots, prefs do).
 */
object ReminderStore {

    private const val PREFS_FILE = "reminder_store"
    private const val KEY_REMINDERS = "reminders"
    const val MAX_REMINDERS = 30

    data class Reminder(
        val id: String = UUID.randomUUID().toString(),
        val text: String,
        /** Wall-clock epoch ms when the reminder fires. */
        val atMs: Long,
        /** Daily repeat: after firing, reschedule +24h instead of removing. */
        val repeatDaily: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

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
        prefs ?: throw IllegalStateException("ReminderStore.init() not called")

    fun all(): List<Reminder> {
        val raw = p().getString(KEY_REMINDERS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    Reminder(
                        id = it.optString("id"),
                        text = it.optString("text"),
                        atMs = it.optLong("atMs"),
                        repeatDaily = it.optBoolean("repeatDaily", false),
                        createdAt = it.optLong("createdAt")
                    )
                }
            }.sortedBy { it.atMs }
        }.getOrDefault(emptyList())
    }

    fun add(reminder: Reminder): Boolean {
        val current = all().toMutableList()
        if (current.size >= MAX_REMINDERS) return false
        current.add(reminder)
        save(current)
        return true
    }

    fun get(id: String): Reminder? = all().firstOrNull { it.id == id }

    fun remove(id: String) = save(all().filterNot { it.id == id })

    /** Fuzzy cancel by text; returns the removed reminder or null. */
    fun removeByText(query: String): Reminder? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        val current = all().toMutableList()
        val hit = current.firstOrNull { it.text.lowercase().contains(q) } ?: return null
        current.remove(hit)
        save(current)
        return hit
    }

    fun update(reminder: Reminder) {
        save(all().filterNot { it.id == reminder.id } + reminder)
    }

    private fun save(list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("text", it.text)
                    .put("atMs", it.atMs)
                    .put("repeatDaily", it.repeatDaily)
                    .put("createdAt", it.createdAt)
            )
        }
        p().edit().putString(KEY_REMINDERS, arr.toString()).apply()
    }
}
