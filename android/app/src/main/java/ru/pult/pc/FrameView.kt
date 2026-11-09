package ru.pult.pc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Показывает картинку экрана компьютера (с сохранением пропорций) и точку-курсор. */
open class FrameView(context: Context) : View(context) {

    protected val density = resources.displayMetrics.density
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    protected val dst = RectF()
    private val path = Path()

    var frame: Bitmap? = null
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }
    var cursorX = -1f
    var cursorY = -1f

    protected open fun bgColor(): Int = Colors.SURFACE
    protected open fun hintLines(): List<String> = listOf("Ждём картинку с компьютера…")

    protected fun computeDst() {
        val f = frame
        if (f == null) {
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            return
        }
        val s = minOf(width.toFloat() / f.width, height.toFloat() / f.height)
        val dw = f.width * s
        val dh = f.height * s
        dst.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val r = 22 * density
        paint.style = Paint.Style.FILL
        paint.color = bgColor()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)

        val f = frame
        if (f != null) {
            computeDst()
            canvas.save()
            path.reset()
            path.addRoundRect(dst, 14 * density, 14 * density, Path.Direction.CW)
            canvas.clipPath(path)
            canvas.drawBitmap(f, null, dst, bmpPaint)
            canvas.restore()
            if (cursorX >= 0f && cursorY >= 0f) {
                val x = dst.left + cursorX * dst.width()
                val y = dst.top + cursorY * dst.height()
                paint.style = Paint.Style.FILL
                paint.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(x, y, 6 * density, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * density
                paint.color = Colors.ACCENT
                canvas.drawCircle(x, y, 6 * density, paint)
            }
        } else {
            paint.style = Paint.Style.FILL
            paint.color = Colors.MUTED
            paint.textSize = 14 * density
            paint.textAlign = Paint.Align.CENTER
            val lines = hintLines()
            var y = height / 2f - (lines.size - 1) * 11 * density
            for (l in lines) {
                canvas.drawText(l, width / 2f, y, paint)
                y += 22 * density
            }
        }
    }
}
