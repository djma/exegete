package dev.margin.reader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/** Converts horizontal drags into one instant page turn before Readium can animate them. */
class InstantSwipeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var onPageSwipe: ((forward: Boolean) -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private var intercepting = false
    private val interceptDistance = 6 * resources.displayMetrics.density
    private val pageDistance = 42 * resources.displayMetrics.density

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                intercepting = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (abs(dx) > interceptDistance && abs(dx) > abs(dy) * 1.35f) {
                    intercepting = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!intercepting) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = event.x - startX
                if (abs(dx) >= pageDistance) {
                    onPageSwipe?.invoke(dx < 0)
                }
                intercepting = false
            }
            MotionEvent.ACTION_CANCEL -> intercepting = false
        }
        return true
    }
}
