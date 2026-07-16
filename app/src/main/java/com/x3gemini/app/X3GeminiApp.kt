package com.x3gemini.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.x3gemini.app.core.config.ApiKeyStore
import com.x3gemini.app.core.config.AssistantStore
import com.x3gemini.app.core.bridge.HudPinStore
import com.x3gemini.app.core.chat.ChatSessionModel
import com.x3gemini.app.core.live.LiveCardEngine
import com.x3gemini.app.core.reminders.ReminderScheduler

/**
 * Application subclass. Owns the single process-wide [ChatSessionModel]
 * — the voice Service (pipeline) writes to it and MainActivity's chat
 * card renders from it via ChatCardBridge, so both must share one
 * instance that outlives any Activity.
 */
class X3GeminiApp : Application() {

    val chatModel: ChatSessionModel by lazy { ChatSessionModel() }

    private val apiKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_SET_API_KEY) return
            val key = intent.getStringExtra("key")?.trim().orEmpty()
            if (key.isBlank()) {
                Log.w(TAG, "SET_API_KEY broadcast without --es key")
                return
            }
            ApiKeyStore.persistFromBroadcast(context, key)
        }
    }

    // Same implicit-broadcast lesson as the API key: this runtime receiver
    // is what lets the plain `adb shell am broadcast -a …SET_INSTRUCTIONS`
    // work while the app is running (the manifest receiver covers the
    // cold-app explicit -n path).
    private val instructionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_SET_INSTRUCTIONS) return
            AssistantStore.init(context)
            AssistantStore.setInstructions(intent.getStringExtra("text"))
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Vendor SDK init — required before any Activity on the Mercury
        // display path. Defensive re-init also happens in MainActivity.
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(this) }
        HudPinStore.init(this)
        AssistantStore.init(this)
        // Re-arm every stored reminder — alarms don't survive process death
        // or reboots, the prefs-backed store does. Missed ones fire now.
        runCatching { ReminderScheduler.rescheduleAll(this) }
        // Live cards keep refreshing process-wide, session or not.
        runCatching { LiveCardEngine.ensureStarted(this) }
        // Runtime-registered key receiver. A manifest receiver does NOT get
        // an IMPLICIT `am broadcast` on Android 8+ (background broadcast
        // limits), so the plain command silently no-ops. A context-
        // registered receiver in the live process DOES receive it — this is
        // what makes `adb shell am broadcast -a …SET_API_KEY --es key …`
        // work without needing an explicit -n component.
        runCatching {
            ContextCompat.registerReceiver(
                this,
                apiKeyReceiver,
                IntentFilter(ACTION_SET_API_KEY),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                instructionsReceiver,
                IntentFilter(ACTION_SET_INSTRUCTIONS),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    companion object {
        private const val TAG = "X3GeminiApp"
        private const val ACTION_SET_API_KEY = "com.x3gemini.app.SET_API_KEY"
        private const val ACTION_SET_INSTRUCTIONS = "com.x3gemini.app.SET_INSTRUCTIONS"
    }
}
