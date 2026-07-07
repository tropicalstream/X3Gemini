package com.x3gemini.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.x3gemini.app.R
import com.x3gemini.app.core.bridge.HudPinStore
import com.x3gemini.app.core.bridge.HudPinStore.HudPin
import com.x3gemini.app.core.live.LiveCardEngine
import java.net.URL
import java.util.Locale

/**
 * HUD pin board — renders the user's pinned notes / pictures / live
 * cards into the area under the HUD strip. Ported from TapInsight
 * beta6-gold; X3Gemini changes:
 *
 *   • The pin zone is the WHOLE under-HUD area (no browser to avoid),
 *     hard-clamped inside the logical 640×480 viewport.
 *   • No URL opening: notes are inert (tap does nothing), live cards
 *     tap-to-refresh, pictures open the fullscreen viewer.
 *
 * Interaction model (glasses trackpad) is unchanged:
 *   • Tap a pin → open it (picture → fullscreen viewer, live card →
 *     refresh now).
 *   • DOUBLE-TAP with the cursor over a pin → "hud modify" mode: the
 *     pin highlights and grows an ✕ (delete) chip. The NEXT tap ends
 *     the mode immediately: on ✕ → delete; anywhere else → the pin
 *     moves to that spot (clamped to the zone) and the position
 *     persists. Double-tap again also exits without changes.
 *
 * Threading: HudPinStore listeners fire on the mutating thread (a
 * voice-tool coroutine when Gemini pins something) — every mutation
 * hops to main via [uiHandler] before touching views.
 */
class HudPinBoardController(
    private val activity: Activity,
    private val board: FrameLayout,
    private val uiHandler: Handler,
    private val forceCursorVisible: () -> Unit,
    private val showToast: (String) -> Unit
) {

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private var subscription: AutoCloseable? = null
    private val pinViews = LinkedHashMap<String, FrameLayout>() // pin id → container
    private var pinsSnapshot: List<HudPin> = emptyList()

    // hud-modify state
    private var modifyPinId: String? = null
    private var fullscreenView: FrameLayout? = null

    private val bitmapCache = LruCache<String, Bitmap>(8)

    fun start() {
        HudPinStore.init(activity)
        subscription?.runCatching { close() }
        subscription = HudPinStore.observe { pins ->
            uiHandler.post { render(pins) }
        }
    }

    fun stop() {
        subscription?.runCatching { close() }
        subscription = null
    }

    /** Re-slot the grid after HUD geometry changes (camera preview on/off). */
    fun refreshZone() {
        if (pinViews.isEmpty() && pinsSnapshot.isEmpty()) return
        render(pinsSnapshot)
    }

    // ------------------------------------------------------------------
    // Zone geometry
    // ------------------------------------------------------------------

    /** Pin zone in BOARD-local px (board is match_parent in the overlay). */
    private data class Zone(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun boardLocalX(view: View): Int {
        val a = IntArray(2); val b = IntArray(2)
        view.getLocationOnScreen(a); board.getLocationOnScreen(b)
        return a[0] - b[0]
    }

    private fun boardLocalY(view: View): Int {
        val a = IntArray(2); val b = IntArray(2)
        view.getLocationOnScreen(a); board.getLocationOnScreen(b)
        return a[1] - b[1]
    }

    /** Zone from the LAST render — carried pins clamp against this. */
    private var lastZone: Zone? = null

    private fun computeZone(): Zone {
        // The whole area under the HUD strip. The strip is 36px tall at
        // y=2, so content starts at y=44 (same top line as TapInsight's
        // calibrated shelf); the rest of the logical viewport is ours.
        val right = if (board.width > 0) board.width - 6 else UNDER_HUD_ZONE.right
        val bottom = if (board.height > 0) board.height - 6 else UNDER_HUD_ZONE.bottom
        return Zone(UNDER_HUD_ZONE.left, UNDER_HUD_ZONE.top, right, maxOf(bottom, UNDER_HUD_ZONE.top + 28))
    }

    /** Clamp a pin's margins so its rect stays fully inside [zone]. */
    private fun clampToZone(lp: FrameLayout.LayoutParams, w: Int, h: Int, zone: Zone) {
        lp.leftMargin = lp.leftMargin.coerceIn(zone.left, maxOf(zone.left, zone.right - w))
        lp.topMargin = lp.topMargin.coerceIn(zone.top, maxOf(zone.top, zone.bottom - h))
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun render(pins: List<HudPin>) {
        pinsSnapshot = pins
        exitModifyMode()
        board.removeAllViews()
        pinViews.clear()
        if (pins.isEmpty()) return
        if (board.width <= 0) {
            // pre-layout — retry once the overlay has real bounds
            board.post { if (board.width > 0) render(pinsSnapshot) }
            return
        }

        val zone = computeZone()
        lastZone = zone
        val gap = dp(6)
        var x = zone.left
        var y = zone.top
        var rowH = 0

        // TWO passes: custom-positioned pins first, so the flow grid can
        // route around every one of them regardless of store order.
        val customRects = mutableListOf<IntArray>() // [l, t, r, b]
        // The camera preview shares the zone — while it's visible, treat
        // its rect as a blocker so grid pins never tile underneath it.
        activity.findViewById<View?>(R.id.unipanelCameraPreviewFrame)?.let { cam ->
            if (cam.visibility == View.VISIBLE && cam.width > 0) {
                val l = boardLocalX(cam)
                val t = boardLocalY(cam)
                customRects += intArrayOf(l, t, l + cam.width, t + cam.height)
            }
        }
        val ordered = pins.sortedBy { if (it.customX >= 0 && it.customY >= 0) 0 else 1 }
        for (pin in ordered) {
            val container = buildPinView(pin)
            val w = container.layoutParams.width
            val h = container.layoutParams.height
            val lp = container.layoutParams as FrameLayout.LayoutParams
            if (pin.customX >= 0 && pin.customY >= 0) {
                lp.leftMargin = pin.customX
                lp.topMargin = pin.customY
                clampToZone(lp, w, h, zone)
                customRects += intArrayOf(
                    lp.leftMargin, lp.topMargin, lp.leftMargin + w, lp.topMargin + h
                )
            } else {
                // Flow grid: wrap at the zone's right edge, skip past any
                // custom pin the candidate cell would overlap, and hard-
                // clamp the result inside the zone.
                var guard = 0
                while (guard++ < 64) {
                    if (x + w > zone.right && x > zone.left) {
                        x = zone.left
                        y += rowH + gap
                        rowH = 0
                        continue
                    }
                    val blocker = customRects.firstOrNull { r ->
                        x < r[2] + gap && x + w + gap > r[0] &&
                            y < r[3] + gap && y + h + gap > r[1]
                    }
                    if (blocker != null) {
                        x = blocker[2] + gap
                        continue
                    }
                    break
                }
                lp.leftMargin = x
                lp.topMargin = y
                clampToZone(lp, w, h, zone)
                x = lp.leftMargin + w + gap
                y = lp.topMargin
                rowH = maxOf(rowH, h)
            }
            container.layoutParams = lp
            board.addView(container)
            pinViews[pin.id] = container
        }
    }

    /** Container FrameLayout: content + (hidden until modify) ✕ chip. */
    private fun buildPinView(pin: HudPin): FrameLayout {
        val container = FrameLayout(activity)
        val (w, h) = when (pin.type) {
            HudPinStore.TYPE_NOTE -> dp(92) to dp(64)
            HudPinStore.TYPE_PICTURE -> dp(64) to dp(48)
            // Live cards grow with their content up to the line cap, so a
            // "top 10 headlines" card is fully readable instead of clipped
            // at 2 lines. Height = header + padding + one row per line.
            HudPinStore.TYPE_LIVE -> {
                val lineCount = pin.content.ifBlank { pin.label }
                    .count { it == '\n' }
                    .plus(1)
                    .coerceIn(1, LiveCardEngine.MAX_CARD_LINES)
                dp(210) to dp(LIVE_HEADER_DP + lineCount * LIVE_LINE_DP)
            }
            else -> dp(92) to dp(46)
        }
        container.layoutParams = FrameLayout.LayoutParams(w, h)
        container.elevation = 6f * density
        container.isClickable = true
        container.isFocusable = true
        container.tag = pin.id

        val content: View = when (pin.type) {
            HudPinStore.TYPE_PICTURE -> buildPictureContent(pin)
            HudPinStore.TYPE_LIVE -> buildLiveContent(pin)
            else -> buildNoteContent(pin)
        }
        content.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        // taps must land on the CONTAINER (the hit-test walks descendants
        // in reverse; a clickable child would steal the tap from it).
        content.isClickable = false
        content.isFocusable = false
        container.addView(content)

        container.setOnClickListener {
            if (modifyPinId != null) exitModifyMode() else openPin(pin)
        }
        return container
    }

    private fun buildNoteContent(pin: HudPin): View {
        val tv = TextView(activity)
        tv.text = pin.payload.ifBlank { pin.label }
        tv.setTextColor(0xFF1B1B10.toInt())
        tv.textSize = 9f
        tv.maxLines = 4
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.setPadding(dp(6), dp(5), dp(6), dp(5))
        // post-it yellow, near-opaque so the dark text survives outdoors
        tv.background = GradientDrawable().apply {
            setColor(0xF2FFEE58.toInt())
            cornerRadius = 2f * density
        }
        return tv
    }

    /**
     * Live card: dark chip. Header = accent label + last-update age;
     * body = the engine's latest text. Stale or never-refreshed cards
     * render dimmed.
     */
    private fun buildLiveContent(pin: HudPin): View {
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(7), dp(3), dp(7), dp(4))
        col.background = GradientDrawable().apply {
            setColor(0xB3000000.toInt())
            cornerRadius = 3f * density
            if (pin.stale) setStroke(dp(1), 0x66FF5252)
        }

        val header = LinearLayout(activity)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val label = TextView(activity)
        label.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        label.text = pin.label.uppercase(Locale.US)
        label.setTextColor(0xFF7FDBFF.toInt())
        label.textSize = 8f
        label.typeface = Typeface.DEFAULT_BOLD
        label.maxLines = 1
        // Cap so a long title still leaves the age beside it (not off-card).
        label.maxWidth = dp(150)
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        header.addView(label)
        // Beside the title — "WORLD CUP · 5m", or a status reason when the
        // card isn't updating: red for a dead/stale source, amber for an
        // informational state like "rate-limited" (throttled, not broken).
        val trailing = pin.statusNote ?: if (pin.stale) "stale" else ageText(pin.updatedAt)
        if (trailing.isNotBlank()) {
            val age = TextView(activity)
            age.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(5); leftMargin = dp(5) }
            age.text = "·  $trailing"
            age.setTextColor(
                when {
                    pin.stale -> 0xFFFF5252.toInt()           // red — dead source
                    pin.statusNote != null -> 0xFFFFB347.toInt() // amber — throttled/info
                    else -> 0x80FFFFFF.toInt()                // dim — normal age
                }
            )
            age.textSize = 8f
            header.addView(age)
        }
        col.addView(header)

        val body = TextView(activity)
        // Clearer than a bare "…": a never-loaded card reads "updating…",
        // and one that's failed enough to be stale reads "unavailable".
        body.text = pin.content.ifBlank { if (pin.stale) "unavailable" else "updating…" }
        body.setTextColor(Color.WHITE)
        body.textSize = 9f
        body.maxLines = LiveCardEngine.MAX_CARD_LINES
        body.ellipsize = android.text.TextUtils.TruncateAt.END
        body.setLineSpacing(0f, 1.05f)
        col.addView(body)

        col.alpha = if (pin.stale || pin.content.isBlank()) 0.72f else 1f
        return col
    }

    private fun ageText(updatedAt: Long): String {
        if (updatedAt <= 0L) return ""
        val mins = (System.currentTimeMillis() - updatedAt) / 60_000L
        return when {
            mins < 1 -> "now"
            mins < 60 -> "${mins}m"
            else -> "${mins / 60}h"
        }
    }

    private fun buildPictureContent(pin: HudPin): View {
        val iv = ImageView(activity)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = GradientDrawable().apply {
            setColor(0xFF10181E.toInt())
            setStroke(dp(1), 0xCCFFFFFF.toInt())
            cornerRadius = 2f * density
        }
        iv.setPadding(dp(1), dp(1), dp(1), dp(1))
        loadPinBitmap(pin) { bmp -> iv.setImageBitmap(bmp) }
        return iv
    }

    private fun openPin(pin: HudPin) {
        when (pin.type) {
            HudPinStore.TYPE_PICTURE -> showFullscreenPicture(pin)
            HudPinStore.TYPE_LIVE -> {
                // No browser to open a source page in — tap = refresh now.
                HudPinStore.requestRefresh(pin.id)
                showToast("Refreshing \"${pin.label}\"…")
            }
            else -> {
                // Notes are inert surfaces — nothing to open on this build.
            }
        }
    }

    // ------------------------------------------------------------------
    // Fullscreen picture viewer (tap anywhere to dismiss)
    // ------------------------------------------------------------------

    private fun showFullscreenPicture(pin: HudPin) {
        dismissFullscreen()
        val overlayRoot = board.parent as? FrameLayout ?: return
        val frame = FrameLayout(activity)
        frame.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        frame.setBackgroundColor(0xE6000000.toInt())
        frame.elevation = 30f * density
        frame.isClickable = true
        frame.isFocusable = true

        val iv = ImageView(activity)
        iv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { setMargins(dp(24), dp(20), dp(24), dp(28)) }
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        frame.addView(iv)

        if (pin.label.isNotBlank()) {
            val caption = TextView(activity)
            caption.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(6) }
            caption.text = pin.label
            caption.setTextColor(Color.WHITE)
            caption.textSize = 11f
            caption.setShadowLayer(3f * density, 0f, 1f, Color.BLACK)
            frame.addView(caption)
        }

        frame.setOnClickListener { dismissFullscreen() }
        overlayRoot.addView(frame)
        fullscreenView = frame
        forceCursorVisible()
        loadPinBitmap(pin) { bmp -> iv.setImageBitmap(bmp) }
    }

    fun dismissFullscreen(): Boolean {
        val v = fullscreenView ?: return false
        (v.parent as? FrameLayout)?.removeView(v)
        fullscreenView = null
        return true
    }

    fun isFullscreenShowing(): Boolean = fullscreenView != null

    private fun loadPinBitmap(pin: HudPin, onReady: (Bitmap) -> Unit) {
        bitmapCache.get(pin.id)?.let { onReady(it); return }
        Thread {
            val bmp: Bitmap? = try {
                val src = pin.payload
                if (src.startsWith("http://") || src.startsWith("https://")) {
                    URL(src).openStream().use { BitmapFactory.decodeStream(it) }
                } else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(src, opts)
                    val sample = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 1280)
                    BitmapFactory.decodeFile(
                        src, BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                }
            } catch (_: Exception) {
                null
            }
            if (bmp != null) {
                bitmapCache.put(pin.id, bmp)
                uiHandler.post { onReady(bmp) }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Hud-modify mode (double-tap with the cursor over a pin)
    // ------------------------------------------------------------------

    /**
     * Double-tap hook. Returns true when the tap landed on a pin and
     * modify mode engaged (caller should consume the gesture and skip
     * the Gemini-toggle stage). Exiting on a second double-tap is the
     * caller's branch — it checks [isInModifyMode] first.
     */
    fun onDoubleTapAt(screenX: Float, screenY: Float): Boolean {
        val hit = pinViews.entries.firstOrNull { (_, v) ->
            viewContains(v, screenX, screenY)
        } ?: return false
        enterModifyMode(hit.key)
        return true
    }

    private fun enterModifyMode(pinId: String) {
        if (modifyPinId != null && modifyPinId != pinId) exitModifyMode()
        val container = pinViews[pinId] ?: return
        modifyPinId = pinId
        forceCursorVisible()
        container.scaleX = 1.08f
        container.scaleY = 1.08f
        container.elevation = 12f * density

        // One chip only: ✕ deletes. Moving needs no chip — the NEXT tap
        // anywhere in the zone places the pin there and the mode exits
        // immediately.
        container.addView(buildChip("✕", 0xE6D32F2F.toInt(), Gravity.TOP or Gravity.END) {
            val id = modifyPinId ?: return@buildChip
            exitModifyMode()
            HudPinStore.remove(id)
            showToast("Pin removed")
        }.also { it.tag = CHIP_TAG })
        showToast("Tap a spot to move it · ✕ deletes")
    }

    private fun buildChip(
        glyph: String,
        color: Int,
        gravity: Int,
        onTap: () -> Unit
    ): TextView {
        val chip = TextView(activity)
        val size = dp(20)
        chip.layoutParams = FrameLayout.LayoutParams(size, size, gravity)
        chip.gravity = Gravity.CENTER
        chip.text = glyph
        chip.setTextColor(Color.WHITE)
        chip.textSize = 10f
        chip.typeface = Typeface.DEFAULT_BOLD
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), Color.WHITE)
        }
        chip.elevation = 14f * density
        chip.isClickable = true
        chip.setOnClickListener { onTap() }
        return chip
    }

    /**
     * Consumes the NEXT tap while modify mode is active — called by
     * MainActivity at the top of the overlay tap dispatch, before the
     * normal hit-test.
     *
     *   • Tap on the ✕ chip → DELETE the pin, exit modify mode.
     *   • Tap anywhere else → MOVE the pin there (centered on the tap,
     *     clamped inside the zone), exit modify mode.
     */
    fun onOverlayTapWhileModify(screenX: Float, screenY: Float): Boolean {
        val id = modifyPinId ?: return false
        val container = pinViews[id] ?: run {
            exitModifyMode()
            return false
        }
        val chip = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == CHIP_TAG }
        if (chip != null && viewContains(chip, screenX, screenY, slackPx = dp(6))) {
            exitModifyMode()
            HudPinStore.remove(id)
            showToast("Pin removed")
            return true
        }
        val boardLoc = IntArray(2)
        board.getLocationOnScreen(boardLoc)
        val w = container.width.takeIf { it > 0 } ?: container.layoutParams.width
        val h = container.height.takeIf { it > 0 } ?: container.layoutParams.height
        val lp = container.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (screenX - boardLoc[0] - w / 2f).toInt()
        lp.topMargin = (screenY - boardLoc[1] - h / 2f).toInt()
        clampToZone(lp, w, h, lastZone ?: computeZone())
        container.layoutParams = lp
        exitModifyMode()
        // persists + re-renders through the store observer
        HudPinStore.updatePosition(id, lp.leftMargin, lp.topMargin)
        return true
    }

    fun isInModifyMode(): Boolean = modifyPinId != null

    fun exitModifyMode() {
        val container = pinViews[modifyPinId] ?: run {
            modifyPinId = null
            return
        }
        modifyPinId = null
        container.scaleX = 1f
        container.scaleY = 1f
        container.elevation = 6f * density
        removeChips(container)
    }

    private fun removeChips(container: FrameLayout) {
        val chips = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.tag == CHIP_TAG }
        chips.forEach { container.removeView(it) }
    }

    private fun viewContains(v: View, screenX: Float, screenY: Float, slackPx: Int = 0): Boolean {
        if (v.visibility != View.VISIBLE || v.width == 0) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return screenX >= loc[0] - slackPx && screenX < loc[0] + v.width + slackPx &&
            screenY >= loc[1] - slackPx && screenY < loc[1] + v.height + slackPx
    }

    companion object {
        private const val CHIP_TAG = "hud_pin_chip"

        // Live-card box sizing (logical px). Header row + vertical padding,
        // then one row per content line at 9sp with 1.05 spacing.
        private const val LIVE_HEADER_DP = 24
        private const val LIVE_LINE_DP = 13

        /**
         * The under-HUD content zone in the overlay's LOGICAL 640×480
         * coordinate space. Top = 44 (2px margin + 36px HUD strip + gap),
         * matching the top line of TapInsight's calibrated shelf; the
         * rest of the viewport belongs to pins / camera preview. Raw px
         * on purpose — the calibration space is overlay units, not dp.
         */
        val UNDER_HUD_ZONE = android.graphics.Rect(6, 44, 634, 474)
    }
}
