package com.x3gemini.app

import android.app.Application
import com.x3gemini.app.core.bridge.HudPinStore
import com.x3gemini.app.core.chat.ChatSessionModel
import com.x3gemini.app.core.live.LiveCardEngine

/**
 * Application subclass. Owns the single process-wide [ChatSessionModel]
 * — the voice Service (pipeline) writes to it and MainActivity's chat
 * card renders from it via ChatCardBridge, so both must share one
 * instance that outlives any Activity.
 */
class X3GeminiApp : Application() {

    val chatModel: ChatSessionModel by lazy { ChatSessionModel() }

    override fun onCreate() {
        super.onCreate()
        // Vendor SDK init — required before any Activity on the Mercury
        // display path. Defensive re-init also happens in MainActivity.
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(this) }
        HudPinStore.init(this)
        // Live cards keep refreshing process-wide, session or not.
        runCatching { LiveCardEngine.ensureStarted(this) }
    }
}
