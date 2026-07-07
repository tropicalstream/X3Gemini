package com.x3gemini.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.x3gemini.app.core.bridge.CameraStateBridge
import com.x3gemini.app.core.bridge.ChatCardBridge
import com.x3gemini.app.core.bridge.HudStateBridge
import com.x3gemini.app.core.bridge.VoiceServiceApi
import com.x3gemini.app.ui.HudPinBoardController

/**
 * X3Gemini — a Gemini Live voice HUD for the RayNeo X3 Pro, carved out
 * of TapInsight's unipanel branch. One Activity, one Service, no
 * browser: under the HUD strip is black space (transparent on the
 * waveguide) shared by the pin board and the camera preview.
 *
 * Controls:
 *   • Right trackpad (cyttsp5_mt) — moves the cursor; the physical tap
 *     arrives as a KEY (KEYCODE_BUTTON_A / DPAD_CENTER): single tap =
 *     click at cursor, DOUBLE tap = toggle the Gemini session (or pin
 *     modify mode when the cursor rests on a pin).
 *   • Left arm (cyttsp6_mt) — DOUBLE tap toggles the camera preview.
 *   • Avatar orb tap — also toggles the Gemini session.
 */
class MainActivity : AppCompatActivity() {

    private val uiHandler = Handler(Looper.getMainLooper())

    // ── Service binding ───────────────────────────────────────────────
    @Volatile private var voiceServiceApi: VoiceServiceApi? = null
    @Volatile private var pendingVoiceActivateUntilMs: Long = 0L
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            voiceServiceApi = service as? VoiceServiceApi
            Log.i(TAG, "Voice service connected (api=${voiceServiceApi != null})")
            installCameraPreviewProvider()
            if (SystemClock.uptimeMillis() < pendingVoiceActivateUntilMs) {
                pendingVoiceActivateUntilMs = 0L
                runCatching { voiceServiceApi?.activateVoice() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Voice service disconnected")
            voiceServiceApi = null
        }
    }

    // ── Cursor state ──────────────────────────────────────────────────
    private var cursorX = 320f
    private var cursorY = 240f
    private var isCursorVisible = false
    private var droppedFirstDelta = false
    private val cursorGain = 0.45f
    private val hideCursorRunnable = Runnable { setCursorVisible(false) }

    // ── Right-arm KEY tap state (physical tap = KEYCODE, not touch) ──
    private var rightArmKeyDownMs: Long = 0L
    private var rightArmKeyTracking: Boolean = false
    private var rightArmKeyLastTapUpMs: Long = 0L
    private var pendingSingleTapClick: Runnable? = null

    // ── Left-arm double-tap state (touch path on cyttsp6_mt) ─────────
    private var leftArmTapDownTimeMs: Long = 0L
    private var leftArmTapDownX = 0f
    private var leftArmTapDownY = 0f
    private var leftArmTapTracking = false
    private var leftArmTapMovedTooFar = false
    private var leftArmLastTapUpMs: Long = 0L

    // ── HUD subscriptions / receivers ─────────────────────────────────
    private var batteryReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hudStateSubscription: AutoCloseable? = null
    private var chatCardSubscription: AutoCloseable? = null
    private var cameraStateSubscription: AutoCloseable? = null
    private var hudPinBoardController: HudPinBoardController? = null

    // ── Chat card state (same display + timeout behaviour as TapInsight:
    //    persists until replaced or dismissed; tap = expand/collapse) ──
    @Volatile private var assistantCardDismissedThroughMs: Long = 0L
    private var isCardExpanded = false
    private var lastRenderedCardText: String = ""

    private var noticeClearRunnable: Runnable? = null

    /**
     * Force 1dp == 1px for the 640×480 logical viewport. Set density
     * exactly ONCE via createConfigurationContext and never touch
     * widthPixels (gotcha #26 — metric mutation compounds).
     */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.densityDpi = DisplayMetrics.DENSITY_MEDIUM
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(application) }
        setContentView(R.layout.activity_main)

        requestRuntimePermissions()

        startHudClockTicker()
        startBatteryReceiver()
        startNetworkObserver()
        setupVoiceOrb()
        startHudStateObserver()
        setupChatCard()
        startCameraStateObserver()
        setupHudPinBoard()

        bindVoiceService()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        runCatching { batteryReceiver?.let { unregisterReceiver(it) } }
        batteryReceiver = null
        runCatching {
            networkCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        hudStateSubscription?.runCatching { close() }
        chatCardSubscription?.runCatching { close() }
        cameraStateSubscription?.runCatching { close() }
        hudPinBoardController?.stop()
        hudPinBoardController = null
        if (serviceBound) runCatching { unbindService(serviceConnection) }
        serviceBound = false
        super.onDestroy()
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    private fun bindVoiceService() {
        val intent = Intent().setClassName(packageName, VoiceServiceApi.SERVICE_FQN)
        serviceBound = runCatching {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        Log.i(TAG, "bindVoiceService: bound=$serviceBound")
    }

    /** Hand the PreviewView's SurfaceProvider to the Service. COMPATIBLE
     *  (TextureView-backed) is REQUIRED: a SurfaceView draws to a layer
     *  BinocularSbsLayout's dispatchDraw can't duplicate (gotcha #26b). */
    private fun installCameraPreviewProvider() {
        val previewView = findViewById<androidx.camera.view.PreviewView?>(
            R.id.unipanelCameraPreviewView
        ) ?: return
        previewView.implementationMode =
            androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
        runCatching {
            voiceServiceApi?.setCameraPreviewSurfaceProvider(previewView.surfaceProvider)
        }
    }

    // ────────────────────────────────────────────────────────────────
    // HUD strip: clock / date / battery / network / G badge
    // ────────────────────────────────────────────────────────────────

    private fun startHudClockTicker() {
        val timeTv = findViewById<TextView?>(R.id.unipanelHudTime) ?: return
        val dateTv = findViewById<TextView?>(R.id.unipanelHudDate)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val dateFmt = java.text.SimpleDateFormat("EEE · MMM d", java.util.Locale.US)
        val ticker = object : Runnable {
            override fun run() {
                try {
                    val now = java.util.Date()
                    timeTv.text = timeFmt.format(now)
                    dateTv?.text = dateFmt.format(now)
                } catch (_: Exception) {}
                uiHandler.postDelayed(this, 30_000L)
            }
        }
        uiHandler.post(ticker)
    }

    private fun startBatteryReceiver() {
        val tv = findViewById<TextView?>(R.id.unipanelHudBattery) ?: return
        val render = { intent: Intent? ->
            try {
                if (intent != null) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    val chargePrefix = if (charging) "⚡ " else ""
                    tv.text = if (pct >= 0) "$chargePrefix$pct%" else "—%"
                }
            } catch (_: Exception) {}
        }
        // Seed with the sticky broadcast (null receiver returns it directly).
        runCatching {
            render(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                render(intent)
            }
        }
        runCatching {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiver = receiver
        }
    }

    /** Compact default-network indicator: Wi-Fi / cellular / offline. */
    private fun startNetworkObserver() {
        val tv = findViewById<TextView?>(R.id.unipanelHudNetwork) ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        fun render() {
            val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            val (text, color) = when {
                caps == null -> "⊘" to 0xFFFF5252.toInt()
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    "Wi-Fi" to 0xFF9FE6B0.toInt()
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    "Cell" to 0xFF7FDBFF.toInt()
                else -> "Net" to 0xFF9FE6B0.toInt()
            }
            uiHandler.post {
                tv.text = text
                tv.setTextColor(color)
            }
        }

        render()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = render()
            override fun onLost(network: Network) = render()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = render()
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Voice orb + HUD state (G badge tint, orb glow, notice line)
    // ────────────────────────────────────────────────────────────────

    private fun setupVoiceOrb() {
        val orb = findViewById<View?>(R.id.unipanelVoiceOrb) ?: return
        orb.setOnClickListener { toggleGeminiSession() }
    }

    private fun startHudStateObserver() {
        hudStateSubscription?.runCatching { close() }
        hudStateSubscription = HudStateBridge.observe { state ->
            uiHandler.post {
                renderAiBadge(state)
                renderVoiceOrb(state)
                renderNotice(state)
            }
        }
    }

    private fun renderAiBadge(state: HudStateBridge.State) {
        val badge = findViewById<TextView?>(R.id.unipanelHudAiBadge) ?: return
        // The "G" reflects Gemini API health, not turn progress: green
        // whenever usable (including IDLE), amber connecting, red error.
        val tint = when (state.connection) {
            HudStateBridge.ConnectionStatus.CONNECTING -> 0xFFFFB347.toInt()
            HudStateBridge.ConnectionStatus.DEGRADED,
            HudStateBridge.ConnectionStatus.ERROR -> 0xFFE57373.toInt()
            else -> 0xFF34D399.toInt()
        }
        runCatching {
            badge.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)
        }
    }

    /** Orb glow: thin dim ring idle, bold red while listening, bold
     *  green while Gemini speaks; breathes with the oscilloscope level. */
    private fun renderVoiceOrb(state: HudStateBridge.State) {
        val glow = findViewById<View?>(R.id.unipanelVoiceOrbGlow) ?: return
        val phase = state.phase
        val idle = phase == HudStateBridge.VoicePhase.IDLE
        val listening = phase == HudStateBridge.VoicePhase.LISTENING ||
            phase == HudStateBridge.VoicePhase.FOLLOW_UP
        glow.setBackgroundResource(
            when {
                idle -> R.drawable.bg_unipanel_orb_ring_idle
                listening -> R.drawable.bg_unipanel_orb_ring_red
                else -> R.drawable.bg_unipanel_orb_ring_green
            }
        )
        val level = state.oscilloscopeLevel.coerceIn(0f, 1f)
        if (idle) {
            glow.alpha = 0.55f
            glow.scaleX = 1f
            glow.scaleY = 1f
        } else {
            glow.alpha = (0.85f + level * 0.15f).coerceIn(0f, 1f)
            val scale = 1f + level * 0.22f
            glow.scaleX = scale
            glow.scaleY = scale
        }
    }

    /** Transient one-line notice under the HUD strip; auto-clears. */
    private fun renderNotice(state: HudStateBridge.State) {
        val tv = findViewById<TextView?>(R.id.unipanelHudNotice) ?: return
        val text = state.notification?.trim().takeUnless { it.isNullOrBlank() }
        noticeClearRunnable?.let { uiHandler.removeCallbacks(it) }
        noticeClearRunnable = null
        if (text == null) {
            tv.visibility = View.GONE
            tv.text = ""
            return
        }
        tv.text = text
        tv.visibility = View.VISIBLE
        val clear = Runnable {
            noticeClearRunnable = null
            if (HudStateBridge.current().notification?.trim() == text) {
                HudStateBridge.update { it.copy(notification = null) }
            } else {
                tv.visibility = View.GONE
            }
        }
        noticeClearRunnable = clear
        uiHandler.postDelayed(clear, NOTICE_DISPLAY_MS)
    }

    /** Convenience for pin-board toasts etc. */
    private fun showNotice(msg: String) {
        HudStateBridge.update { it.copy(notification = msg) }
    }

    // ────────────────────────────────────────────────────────────────
    // Chat card (same display + timeout behaviour as TapInsight)
    // ────────────────────────────────────────────────────────────────

    private fun setupChatCard() {
        val card = findViewById<TextView?>(R.id.unipanelMiniCard1) ?: return
        val scroll = findViewById<View?>(R.id.unipanelMiniCardScroll) ?: return
        // onClick goes on the TextView, not the ScrollView —
        // ScrollView.onTouchEvent never calls performClick (gotcha #20).
        // Tap toggles the expanded reader; collapsing keeps the card up.
        card.setOnClickListener {
            val text = card.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            isCardExpanded = !isCardExpanded
            scroll.visibility = View.VISIBLE
            repositionAssistantCard()
            (scroll as? ScrollView)?.post {
                if (isCardExpanded) scroll.scrollTo(0, 0)
                else scroll.fullScroll(View.FOCUS_DOWN)
            }
        }

        chatCardSubscription?.runCatching { close() }
        chatCardSubscription = ChatCardBridge.observe { cards ->
            uiHandler.post { renderAssistantCard(card, scroll, cards) }
        }
        scroll.post { repositionAssistantCard() }
    }

    /**
     * Pure render: show the most recent ASSISTANT card, auto-scroll to
     * the newest text as it streams. The card PERSISTS — no auto-hide;
     * it stays until a newer reply replaces it or a right-arm
     * double-tap dismisses it (exitGeminiFully).
     */
    private fun renderAssistantCard(
        card: TextView,
        scroll: View,
        cards: List<ChatCardBridge.Card>
    ) {
        val latestAssistant = cards.lastOrNull { !it.fromUser && it.text.isNotBlank() }
            ?.takeIf { it.timestampMs > assistantCardDismissedThroughMs }
            ?.text
        if (latestAssistant == null) {
            card.text = ""
            scroll.visibility = View.GONE
            lastRenderedCardText = ""
            isCardExpanded = false
            return
        }
        // Only an explicit tap expands a card: a genuinely NEW reply
        // resets to collapsed; a streaming continuation keeps the state.
        val isContinuation = lastRenderedCardText.isNotEmpty() &&
            latestAssistant.startsWith(lastRenderedCardText)
        if (!isContinuation) {
            isCardExpanded = false
        }
        lastRenderedCardText = latestAssistant
        card.text = latestAssistant
        scroll.visibility = View.VISIBLE
        repositionAssistantCard()
        scroll.post {
            repositionAssistantCard()
            (scroll as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** Card lane: centered under the HUD strip, floating above the
     *  camera preview; expanded reader fills most of the viewport. */
    private fun repositionAssistantCard() {
        val cardView = findViewById<View?>(R.id.unipanelMiniCardScroll) ?: return
        val overlay = findViewById<ViewGroup?>(R.id.unipanelOverlay) ?: return
        if (overlay.width <= 0) return

        val expanded = isCardExpanded
        val width: Int
        val height: Int
        val left: Int
        val top: Int
        if (expanded) {
            left = 8
            top = HUD_CONTENT_TOP
            width = overlay.width - 16
            height = (overlay.height - top - 8).coerceAtLeast(120)
        } else {
            width = 300.coerceAtMost(overlay.width - 16)
            height = 76
            left = ((overlay.width - width) / 2).coerceAtLeast(8)
            top = HUD_CONTENT_TOP + 4
        }

        val lp = cardView.layoutParams as? FrameLayout.LayoutParams ?: return
        var changed = false
        if (lp.leftMargin != left) { lp.leftMargin = left; changed = true }
        if (lp.topMargin != top) { lp.topMargin = top; changed = true }
        if (lp.width != width) { lp.width = width; changed = true }
        if (lp.height != height) { lp.height = height; changed = true }
        if (lp.gravity != (Gravity.TOP or Gravity.START)) {
            lp.gravity = Gravity.TOP or Gravity.START
            changed = true
        }
        if (changed) cardView.layoutParams = lp
    }

    // ────────────────────────────────────────────────────────────────
    // Camera preview (4:3, large, centered under the HUD)
    // ────────────────────────────────────────────────────────────────

    private fun startCameraStateObserver() {
        val dot = findViewById<View?>(R.id.unipanelVisionDot)
        val previewFrame = findViewById<View?>(R.id.unipanelCameraPreviewFrame)
        cameraStateSubscription?.runCatching { close() }
        cameraStateSubscription = CameraStateBridge.observe { on ->
            uiHandler.post {
                dot?.visibility = if (on) View.VISIBLE else View.GONE
                previewFrame?.visibility = if (on) View.VISIBLE else View.GONE
                if (on) repositionCameraPreview()
                // The preview is a grid blocker — re-slot pins once it
                // has its final bounds.
                previewFrame?.post { hudPinBoardController?.refreshZone() }
            }
        }
    }

    /**
     * Size the preview to the standard X3 camera proportions (4:3) at
     * the largest size that fits under the HUD strip, centered. All
     * units are logical px (1dp == 1px at DENSITY_MEDIUM).
     */
    private fun repositionCameraPreview() {
        val preview = findViewById<View?>(R.id.unipanelCameraPreviewFrame) ?: return
        if (preview.visibility != View.VISIBLE) return
        val overlay = findViewById<View?>(R.id.unipanelOverlay) ?: return
        if (overlay.width <= 0) return

        val top = HUD_CONTENT_TOP
        var h = (overlay.height - top - 8).coerceAtLeast(96)
        var w = h * 4 / 3
        val maxW = overlay.width - 16
        if (w > maxW) {
            w = maxW
            h = w * 3 / 4
        }
        val left = ((overlay.width - w) / 2).coerceAtLeast(8)

        val lp = preview.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.topMargin != top || lp.leftMargin != left || lp.width != w || lp.height != h) {
            lp.topMargin = top
            lp.leftMargin = left
            lp.marginStart = left
            lp.width = w
            lp.height = h
            preview.layoutParams = lp
        }
        repositionAssistantCard()
    }

    // ────────────────────────────────────────────────────────────────
    // Pin board
    // ────────────────────────────────────────────────────────────────

    private fun setupHudPinBoard() {
        val board = findViewById<FrameLayout?>(R.id.unipanelPinBoard) ?: return
        val controller = HudPinBoardController(
            activity = this,
            board = board,
            uiHandler = uiHandler,
            forceCursorVisible = { setCursorVisible(true) },
            showToast = { msg -> showNotice(msg) }
        )
        hudPinBoardController = controller
        controller.start()
    }

    // ────────────────────────────────────────────────────────────────
    // Gemini session toggle + full exit
    // ────────────────────────────────────────────────────────────────

    private fun toggleGeminiSession() {
        val api = voiceServiceApi
        if (api == null) {
            Log.w(TAG, "toggle: service not bound yet — queuing activation")
            pendingVoiceActivateUntilMs = SystemClock.uptimeMillis() + 4000L
            bindVoiceService()
            return
        }
        val phase = HudStateBridge.current().phase
        if (phase == HudStateBridge.VoicePhase.IDLE) {
            Log.i(TAG, "toggle: activateVoice()")
            runCatching { api.activateVoice() }
        } else {
            Log.i(TAG, "toggle: exitGeminiFully() (phase=$phase)")
            exitGeminiFully()
        }
    }

    /** Full Gemini exit: session + camera + chat card, same teardown
     *  set as TapInsight's right-arm double-tap. */
    private fun exitGeminiFully() {
        val api = voiceServiceApi
        val lastAssistantTimestamp = ChatCardBridge.current()
            .asSequence()
            .filter { !it.fromUser && it.text.isNotBlank() }
            .map { it.timestampMs }
            .maxOrNull()
            ?: System.currentTimeMillis()
        assistantCardDismissedThroughMs =
            maxOf(assistantCardDismissedThroughMs, lastAssistantTimestamp)

        runCatching { if (api?.isCameraOn() == true) api.toggleCamera() }
        runCatching { api?.shutdownVoice() }
        HudStateBridge.update {
            it.copy(
                phase = HudStateBridge.VoicePhase.IDLE,
                connection = HudStateBridge.ConnectionStatus.IDLE,
                transcript = null,
                oscilloscopeLevel = 0f,
                notification = null
            )
        }
        runCatching { CameraStateBridge.publish(false) }
        runCatching {
            findViewById<View?>(R.id.unipanelCameraPreviewFrame)?.visibility = View.GONE
            findViewById<View?>(R.id.unipanelVisionDot)?.visibility = View.GONE
        }
        isCardExpanded = false
        runCatching {
            findViewById<View?>(R.id.unipanelMiniCardScroll)?.visibility = View.GONE
            findViewById<TextView?>(R.id.unipanelMiniCard1)?.text = ""
        }
        Log.i(TAG, "Full Gemini exit (session + camera + chat card)")
    }

    private fun toggleCamera() {
        val api = voiceServiceApi
        if (api == null) {
            Log.w(TAG, "toggleCamera: service not bound yet")
            bindVoiceService()
            return
        }
        installCameraPreviewProvider()
        runCatching { api.toggleCamera() }
    }

    // ────────────────────────────────────────────────────────────────
    // Input: trackpad routing, cursor, taps
    // ────────────────────────────────────────────────────────────────

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val deviceName = ev.device?.name
            ?: InputDevice.getDevice(ev.deviceId)?.name.orEmpty()

        // Left arm (cyttsp6_mt) — volume pad; the ONLY app gesture on it
        // is the camera double-tap. Never let it move the cursor.
        if (deviceName.contains("cyttsp6", ignoreCase = true)) {
            handleLeftArmTouch(ev)
            return true
        }

        // Right trackpad (cyttsp5_mt) — cursor movement.
        if (deviceName.contains("cyttsp5", ignoreCase = true)) {
            handleTrackpadCursorTouch(ev)
            return true
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Trackpad ACTION_SCROLL — nothing scrollable to route it to.
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) return true
        return super.dispatchGenericMotionEvent(event)
    }

    /** Left-arm double-tap → toggle camera. Short taps with move
     *  tolerance; two qualifying UPs inside the window fire. */
    private fun handleLeftArmTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                leftArmTapDownTimeMs = SystemClock.uptimeMillis()
                leftArmTapDownX = ev.x
                leftArmTapDownY = ev.y
                leftArmTapMovedTooFar = false
                leftArmTapTracking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (leftArmTapTracking && !leftArmTapMovedTooFar) {
                    val dx = ev.x - leftArmTapDownX
                    val dy = ev.y - leftArmTapDownY
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) >
                            LEFT_ARM_TAP_MOVE_TOLERANCE_PX) {
                        leftArmTapMovedTooFar = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val wasTracking = leftArmTapTracking
                val movedTooFar = leftArmTapMovedTooFar
                leftArmTapTracking = false
                if (!wasTracking || movedTooFar) {
                    leftArmLastTapUpMs = 0L
                    return
                }
                val elapsed = SystemClock.uptimeMillis() - leftArmTapDownTimeMs
                if (elapsed >= TAP_MAX_MS) {
                    leftArmLastTapUpMs = 0L
                    return
                }
                val now = SystemClock.uptimeMillis()
                val gap = now - leftArmLastTapUpMs
                val isDoubleTap = leftArmLastTapUpMs > 0L &&
                    gap in DOUBLE_TAP_MIN_GAP_MS..DOUBLE_TAP_WINDOW_MS
                if (isDoubleTap) {
                    leftArmLastTapUpMs = 0L
                    Log.i(TAG, "Left-arm double-tap (gap=${gap}ms) → toggle camera")
                    toggleCamera()
                } else {
                    leftArmLastTapUpMs = now
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                leftArmTapTracking = false
                leftArmLastTapUpMs = 0L
            }
        }
    }

    /** Right trackpad finger motion → cursor. Drop the first delta of
     *  each touch sequence (it jumps); gain 0.45. */
    private fun handleTrackpadCursorTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                droppedFirstDelta = false
                lastTrackpadX = ev.x
                lastTrackpadY = ev.y
                setCursorVisible(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastTrackpadX
                val dy = ev.y - lastTrackpadY
                lastTrackpadX = ev.x
                lastTrackpadY = ev.y
                if (!droppedFirstDelta) {
                    droppedFirstDelta = true
                    return
                }
                moveCursorBy(dx * cursorGain, dy * cursorGain)
            }
        }
    }

    private var lastTrackpadX = 0f
    private var lastTrackpadY = 0f

    private fun moveCursorBy(dx: Float, dy: Float) {
        val container = findViewById<View?>(R.id.mainContainer) ?: return
        val maxW = (container.width.takeIf { it > 0 } ?: 640).toFloat()
        val maxH = (container.height.takeIf { it > 0 } ?: 480).toFloat()
        cursorX = (cursorX + dx).coerceIn(0f, maxW - 1f)
        cursorY = (cursorY + dy).coerceIn(0f, maxH - 1f)
        setCursorVisible(true)
        updateCursorView()
    }

    private fun updateCursorView() {
        val cursor = findViewById<ImageView?>(R.id.cursorView) ?: return
        cursor.translationX = cursorX
        cursor.translationY = cursorY
    }

    private fun setCursorVisible(visible: Boolean) {
        val cursor = findViewById<ImageView?>(R.id.cursorView) ?: return
        isCursorVisible = visible
        cursor.visibility = if (visible) View.VISIBLE else View.GONE
        uiHandler.removeCallbacks(hideCursorRunnable)
        if (visible) {
            updateCursorView()
            uiHandler.postDelayed(hideCursorRunnable, CURSOR_IDLE_HIDE_MS)
        }
    }

    /** Cursor position in absolute screen coordinates. */
    private fun cursorInteractionPoint(): Pair<Float, Float> {
        val container = findViewById<View?>(R.id.mainContainer)
        val loc = IntArray(2)
        container?.getLocationOnScreen(loc)
        return (cursorX + loc[0]) to (cursorY + loc[1])
    }

    /**
     * The right-arm physical tap arrives as a KEY event
     * (KEYCODE_BUTTON_A / KEYCODE_DPAD_CENTER), never as a touch.
     * Single tap = click at cursor (after the double-tap window);
     * double tap = pin modify mode when over a pin, else toggle the
     * Gemini session. DOWN is never consumed.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isTapKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
        if (!isTapKey) return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    rightArmKeyDownMs = SystemClock.uptimeMillis()
                    rightArmKeyTracking = true
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (!rightArmKeyTracking) return true
                rightArmKeyTracking = false
                val elapsed = SystemClock.uptimeMillis() - rightArmKeyDownMs
                if (elapsed >= TAP_MAX_MS) {
                    rightArmKeyLastTapUpMs = 0L
                    return true
                }
                val now = SystemClock.uptimeMillis()
                val previous = rightArmKeyLastTapUpMs
                val gap = now - previous
                val isDoubleTap = previous > 0L &&
                    gap in DOUBLE_TAP_MIN_GAP_MS..DOUBLE_TAP_WINDOW_MS
                if (isDoubleTap) {
                    rightArmKeyLastTapUpMs = 0L
                    pendingSingleTapClick?.let { uiHandler.removeCallbacks(it) }
                    pendingSingleTapClick = null
                    onRightArmDoubleTap(gap)
                } else {
                    rightArmKeyLastTapUpMs = now
                    // Schedule the single-tap click; a second tap inside
                    // the window preempts it.
                    pendingSingleTapClick?.let { uiHandler.removeCallbacks(it) }
                    val click = Runnable {
                        pendingSingleTapClick = null
                        performClickAtCursor()
                    }
                    pendingSingleTapClick = click
                    uiHandler.postDelayed(click, DOUBLE_TAP_WINDOW_MS + 20L)
                }
                return true
            }
        }
        return true
    }

    private fun onRightArmDoubleTap(gapMs: Long) {
        Log.i(TAG, "Right-arm double-tap (gap=${gapMs}ms)")
        // Pin board gets first refusal: exit modify mode, or enter it
        // when the cursor rests on a pin (move/delete flow, as before).
        val controller = hudPinBoardController
        if (controller != null) {
            if (controller.isInModifyMode()) {
                controller.exitModifyMode()
                return
            }
            val pt = cursorInteractionPoint()
            if (controller.onDoubleTapAt(pt.first, pt.second)) {
                setCursorVisible(true)
                return
            }
        }
        // Otherwise: toggle the Gemini session (activate ↔ full exit).
        toggleGeminiSession()
    }

    /** Synthetic click at the cursor through the overlay hit-test chain. */
    private fun performClickAtCursor() {
        val pt = cursorInteractionPoint()
        val handled = dispatchOverlayTouchIfHit(pt.first, pt.second)
        Log.d(TAG, "click at cursor (${pt.first}, ${pt.second}) handled=$handled")
    }

    /**
     * Three-state overlay hit-test (ported from TapInsight):
     *   1. Empty transparent region → nothing happens (black space).
     *   2. Inert visual surface     → consume, do nothing.
     *   3. Interactive widget       → dispatch synthetic DOWN+UP.
     * Pin modify mode consumes the NEXT tap before any hit-test.
     */
    private fun dispatchOverlayTouchIfHit(screenX: Float, screenY: Float): Boolean {
        hudPinBoardController?.let { c ->
            if (c.isInModifyMode() && c.onOverlayTapWhileModify(screenX, screenY)) {
                return true
            }
        }
        val hit = findOverlayHit(screenX, screenY) ?: return false
        if (!hit.isInteractive) return true

        val targetLocation = IntArray(2)
        hit.view.getLocationOnScreen(targetLocation)
        val localX = screenX - targetLocation[0]
        val localY = screenY - targetLocation[1]
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, localX, localY, 0)
        val up = MotionEvent.obtain(now, now + 1L, MotionEvent.ACTION_UP, localX, localY, 0)
        try {
            hit.view.dispatchTouchEvent(down)
            hit.view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        return true
    }

    private data class OverlayHit(val view: View, val isInteractive: Boolean)

    private fun findOverlayHit(screenX: Float, screenY: Float): OverlayHit? {
        val overlay = findViewById<View?>(R.id.unipanelOverlay) ?: return null
        if (overlay.visibility != View.VISIBLE) return null
        if (overlay !is ViewGroup) return null
        for (i in overlay.childCount - 1 downTo 0) {
            val child = overlay.getChildAt(i) ?: continue
            val hit = findOverlayHitDescendant(child, screenX, screenY)
            if (hit != null) return hit
        }
        return null
    }

    /**
     * Depth-first, reverse child order (visually-on-top wins). An
     * INTERACTIVE descendant always wins; an INERT descendant must not
     * short-circuit a clickable ancestor (the orb's glow child would
     * otherwise swallow the orb's tap).
     */
    private fun findOverlayHitDescendant(
        root: View,
        screenX: Float,
        screenY: Float
    ): OverlayHit? {
        if (root.visibility != View.VISIBLE) return null
        var inertDescendant: OverlayHit? = null
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i) ?: continue
                val hit = findOverlayHitDescendant(child, screenX, screenY)
                if (hit != null) {
                    if (hit.isInteractive) return hit
                    if (inertDescendant == null) inertDescendant = hit
                }
            }
        }
        val clickable = root.isClickable
        val surface = root.background?.let { !isTransparentBackground(it) } ?: false
        if (!clickable && !surface) return inertDescendant
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        val right = left + root.width
        val bottom = top + root.height
        if (screenX < left || screenX >= right || screenY < top || screenY >= bottom) {
            return inertDescendant
        }
        if (clickable) return OverlayHit(root, isInteractive = true)
        return inertDescendant ?: OverlayHit(root, isInteractive = false)
    }

    private fun isTransparentBackground(bg: android.graphics.drawable.Drawable): Boolean {
        if (bg is android.graphics.drawable.ColorDrawable) {
            return ((bg.color ushr 24) and 0xFF) == 0
        }
        return false
    }

    companion object {
        private const val TAG = "X3GeminiMain"

        /** y where under-HUD content starts (2 + 36px strip + gap). */
        private const val HUD_CONTENT_TOP = 46

        // Tap timing — TapInsight-proven constants. The 40ms floor
        // filters a single physical tap echoing as two keycodes.
        private const val TAP_MAX_MS = 400L
        private const val DOUBLE_TAP_MIN_GAP_MS = 40L
        private const val DOUBLE_TAP_WINDOW_MS = 320L

        private const val LEFT_ARM_TAP_MOVE_TOLERANCE_PX = 60f
        private const val CURSOR_IDLE_HIDE_MS = 6_000L
        private const val NOTICE_DISPLAY_MS = 3_500L
    }
}
