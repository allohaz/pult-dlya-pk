package ru.pult.pc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Блокировка от случайных нажатий. Чтобы снять — удерживай палец чуть больше секунды.
 * Кнопки громкости при этом по-прежнему листают слайды.
 */
class LockView(context: Context, private val onUnlock: () -> Unit) : View(context) {

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var progress = 0f
    private var anim: ValueAnimator? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1100
                    addUpdateListener {
                        progress = it.animatedValue as Float
                        invalidate()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (progress >= 0.999f) onUnlock()
                        }
                    })
                    start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                anim?.cancel()
                progress = 0f
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = 0xF2000000.toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)

        val cx = width / 2f
        val cy = height / 2f
        val r = 56 * d
        p.style = Paint.Style.STROKE
        p.strokeWidth = 6 * d
        p.strokeCap = Paint.Cap.ROUND
        p.color = Colors.SURFACE2
        c.drawCircle(cx, cy, r, p)
        p.color = Colors.ACCENT
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, -90f, 360f * progress, false, p)

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.color = Colors.TEXT
        p.textSize = 20 * d
        c.drawText("Экран заблокирован", cx, cy - r - 36 * d, p)
        p.color = Colors.MUTED
        p.textSize = 14 * d
        c.drawText("Удерживай, чтобы разблокировать", cx, cy + r + 40 * d, p)
        c.drawText("Кнопки громкости листают слайды", cx, cy + r + 64 * d, p)
    }
}
