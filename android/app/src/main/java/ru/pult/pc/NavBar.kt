package ru.pult.pc

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/** Нижняя панель вкладок: значки рисуются кодом, плашка выбранной вкладки плавно переезжает. */
class NavBar(
    context: Context,
    private val labels: List<String>,
    private val onSelect: (Int) -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private var current = 0
    private var pos = 0f
    private var anim: ValueAnimator? = null

    fun choose(i: Int, animate: Boolean) {
        current = i
        anim?.cancel()
        if (!animate) {
            pos = i.toFloat()
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(pos, i.toFloat()).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (64 * d).toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = (e.x / (width.toFloat() / labels.size)).toInt().coerceIn(0, labels.size - 1)
            if (i != current) {
                choose(i, true)
                onSelect(i)
            }
        }
        return true
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    override fun onDraw(c: Canvas) {
        val n = labels.size
        val cw = width.toFloat() / n

        p.style = Paint.Style.FILL
        p.color = Colors.SURFACE
        rect.set(0f, 4 * d, width.toFloat(), height - 4 * d)
        c.drawRoundRect(rect, 24 * d, 24 * d, p)

        val cx = cw * (pos + 0.5f)
        p.color = 0x447C5CFF
        rect.set(cx - 32 * d, 9 * d, cx + 32 * d, height - 9 * d)
        c.drawRoundRect(rect, 18 * d, 18 * d, p)

        for (i in 0 until n) {
            val sel = 1f - minOf(1f, abs(pos - i))
            val col = blend(Colors.MUTED, Colors.TEXT, sel)
            val x = cw * (i + 0.5f)
            icon(c, i, x, height * 0.38f, 20 * d, col)
            p.style = Paint.Style.FILL
            p.color = col
            p.textSize = 11 * d
            p.textAlign = Paint.Align.CENTER
            c.drawText(labels[i], x, height - 14 * d, p)
        }
    }

    private fun icon(c: Canvas, i: Int, cx: Float, cy: Float, s: Float, col: Int) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = col
        val h = s / 2
        when (i) {
            0 -> { // доклад: экран с треугольником
                rect.set(cx - h, cy - h * 0.8f, cx + h, cy + h * 0.4f)
                c.drawRoundRect(rect, 4 * d, 4 * d, p)
                path.reset()
                path.moveTo(cx - h * 0.2f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.35f, cy - h * 0.2f)
                path.lineTo(cx - h * 0.2f, cy + h * 0.1f)
                path.close()
                c.drawPath(path, p)
                c.drawLine(cx - h * 0.5f, cy + h * 0.85f, cx + h * 0.5f, cy + h * 0.85f, p)
            }
            1 -> { // мышь
                rect.set(cx - h * 0.55f, cy - h, cx + h * 0.55f, cy + h)
                c.drawRoundRect(rect, h * 0.55f, h * 0.55f, p)
                c.drawLine(cx, cy - h, cx, cy - h * 0.15f, p)
            }
            2 -> { // игра: геймпад
                rect.set(cx - h * 1.1f, cy - h * 0.55f, cx + h * 1.1f, cy + h * 0.55f)
                c.drawRoundRect(rect, h * 0.5f, h * 0.5f, p)
                c.drawLine(cx - h * 0.65f, cy, cx - h * 0.25f, cy, p)
                c.drawLine(cx - h * 0.45f, cy - h * 0.2f, cx - h * 0.45f, cy + h * 0.2f, p)
                c.drawLine(cx + h * 0.35f, cy - h * 0.1f, cx + h * 0.35f, cy - h * 0.1f, p)
                c.drawLine(cx + h * 0.65f, cy + h * 0.1f, cx + h * 0.65f, cy + h * 0.1f, p)
            }
            3 -> { // медиа: круг с треугольником
                c.drawCircle(cx, cy, h, p)
                path.reset()
                path.moveTo(cx - h * 0.25f, cy - h * 0.45f)
                path.lineTo(cx + h * 0.5f, cy)
                path.lineTo(cx - h * 0.25f, cy + h * 0.45f)
                path.close()
                c.drawPath(path, p)
            }
            4 -> { // клавиши
                rect.set(cx - h, cy - h * 0.6f, cx + h, cy + h * 0.6f)
                c.drawRoundRect(rect, 4 * d, 4 * d, p)
                for (k in -1..1) {
                    val x = cx + k * h * 0.5f
                    c.drawLine(x, cy - h * 0.2f, x, cy - h * 0.2f, p)
                }
                c.drawLine(cx - h * 0.5f, cy + h * 0.25f, cx + h * 0.5f, cy + h * 0.25f, p)
            }
            else -> { // ещё: три точки
                p.style = Paint.Style.FILL
                for (k in -1..1) c.drawCircle(cx + k * h * 0.6f, cy, 2.2f * d, p)
            }
        }
    }
}
