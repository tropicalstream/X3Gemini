package com.x3gemini.app.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Side-by-side binocular compositor.
 *
 * The first child is treated as a single logical viewport and measured to half
 * of the physical width, then drawn twice: left eye and right eye.
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var remapCurrentTouchSequence = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        require(childCount == 1) {
            "BinocularSbsLayout expects exactly one logical viewport child."
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val child = getChildAt(0) ?: return
        val logicalWidth = logicalViewportWidth(measuredWidth)
        val logicalHeight = measuredHeight.coerceAtLeast(0)

        val childWidthSpec = MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(logicalHeight, MeasureSpec.EXACTLY)
        child.measure(childWidthSpec, childHeightSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val child = getChildAt(0)
        if (child == null || child.visibility == GONE) {
            return
        }

        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return

        val drawTime = drawingTime

        canvas.save()
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawTime)
        canvas.restore()

        canvas.save()
        canvas.translate(logicalWidth.toFloat(), 0f)
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawTime)
        canvas.restore()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                remapCurrentTouchSequence = ev.getX(0) >= logicalWidth
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val shouldRemap = remapCurrentTouchSequence
                remapCurrentTouchSequence = false
                if (!shouldRemap) return super.dispatchTouchEvent(ev)
                val mapped = MotionEvent.obtain(ev)
                mapped.offsetLocation(-logicalWidth.toFloat(), 0f)
                val handled = super.dispatchTouchEvent(mapped)
                mapped.recycle()
                return handled
            }
        }

        if (!remapCurrentTouchSequence) return super.dispatchTouchEvent(ev)

        val mapped = MotionEvent.obtain(ev)
        mapped.offsetLocation(-logicalWidth.toFloat(), 0f)
        val handled = super.dispatchTouchEvent(mapped)
        mapped.recycle()
        return handled
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return super.dispatchGenericMotionEvent(event)

        val primaryPointerX = event.getX(0)
        if (primaryPointerX < logicalWidth) {
            return super.dispatchGenericMotionEvent(event)
        }

        val mapped = MotionEvent.obtain(event)
        mapped.offsetLocation(-logicalWidth.toFloat(), 0f)
        val handled = super.dispatchGenericMotionEvent(mapped)
        mapped.recycle()
        return handled
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        // Mirror rendering needs both halves redrawn whenever logical content changes.
        invalidate()
    }

    private fun logicalViewportWidth(totalWidth: Int): Int {
        return (totalWidth / 2).coerceAtLeast(0)
    }
}
