package com.x3gemini.app.core.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Gemini API key resolution for X3Gemini. There is no companion app
 * and no login screen — the key arrives over adb, one of two ways
 * (documented in README.md):
 *
 *   1. File push (survives reinstalls of the same build):
 *      adb push gemini_api_key.txt \
 *        /sdcard/Android/data/com.x3gemini.app/files/gemini_api_key.txt
 *
 *   2. Broadcast (persisted to SharedPreferences):
 *      adb shell am broadcast -a com.x3gemini.app.SET_API_KEY \
 *        --es key "AIza..."
 *
 * Resolution order: pushed file → broadcast-persisted pref. The file
 * wins so a re-push always takes effect immediately.
 */
object ApiKeyStore {

    private const val TAG = "ApiKeyStore"
    private const val KEY_FILE_NAME = "gemini_api_key.txt"
    private const val PREFS_FILE = "x3gemini_config"
    private const val PREF_KEY = "gemini_api_key"

    /** Cheap cache — the file is tiny but this runs on hot paths. */
    @Volatile private var cachedKey: String? = null
    @Volatile private var cachedAtMs: Long = 0L
    private const val CACHE_TTL_MS = 10_000L

    fun resolve(context: Context): String? {
        val now = System.currentTimeMillis()
        cachedKey?.let { if (now - cachedAtMs < CACHE_TTL_MS) return it }

        val fromFile = runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching null
            val f = File(dir, KEY_FILE_NAME)
            if (f.exists()) f.readText().trim().takeIf { it.isNotBlank() } else null
        }.getOrNull()

        val key = fromFile ?: context
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(PREF_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        cachedKey = key
        cachedAtMs = now
        return key
    }

    fun persistFromBroadcast(context: Context, key: String) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY, key.trim())
            .apply()
        cachedKey = key.trim()
        cachedAtMs = System.currentTimeMillis()
        Log.i(TAG, "API key persisted from broadcast (${key.trim().length} chars)")
    }

    /** Drop the cache so the next resolve() re-reads the pushed file. */
    fun invalidateCache() {
        cachedKey = null
        cachedAtMs = 0L
    }
}

/** Receiver for `adb shell am broadcast -a com.x3gemini.app.SET_API_KEY --es key ...` */
class ApiKeyBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.x3gemini.app.SET_API_KEY") return
        val key = intent.getStringExtra("key")?.trim().orEmpty()
        if (key.isBlank()) {
            Log.w("ApiKeyStore", "SET_API_KEY broadcast without --es key")
            return
        }
        ApiKeyStore.persistFromBroadcast(context, key)
    }
}
