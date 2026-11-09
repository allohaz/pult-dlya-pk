package ru.pult.pc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Тачпад (с картинкой экрана ПК под пальцем, если она есть):
 *  - один палец — двигать курсор
 *  - короткое касание — клик (два касания подряд — двойной клик)
 *  - два пальца вверх/вниз — прокрутка, короткое касание двумя пальцами — правый клик
 *  - удержать палец и потом двигать — перетаскивание
 *  - режим «касание» (direct): курсор прыгает туда, куда ткнул на картинке
 * Движения объединяются и уходят не чаще ~60 раз в секунду.
 */
class TouchpadView(context: Context, private val remote: Remote) : FrameView(context) {

    var direct = false

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val main = Handler(Looper.getMainLooper())

    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var dragging = false
    private var twoFinger = false
    private var scrolled = false

    private var accX = 0f
    private var accY = 0f
    private var pendX = 0
    private var pendY = 0
    private var scrollAcc = 0f
    private var flushScheduled = false

    private var absScheduled = false
    private var absX = 0f
    private var absY = 0f

    override fun bgColor(): Int = if (dragging) Colors.ACCENT else Colors.SURFACE

    override fun hintLines(): List<String> = listOf(
        "Двигай пальцем. Касание — клик",
        "Два пальца — прокрутка и правый клик",
        "Удержи и двигай — перетаскивание"
    )

    private val flush = Runnable {
        flushScheduled = false
        if (pendX != 0 || pendY != 0) {
            remote.move(pendX, pendY)
            pendX = 0
            pendY = 0
        }
    }

    private val absRun = Runnable {
        absScheduled = false
        remote.moveTo(absX, absY)
    }

    private val startDrag = Runnable {
        if (!moved && !twoFinger) {
            dragging = true
            remote.mouseButton(1, true)
            invalidate()
        }
    }

    private fun useDirect(): Boolean = direct && frame != null

    private fun toNorm(x: Float, y: Float) {
        computeDst()
        absX = ((x - dst.left) / dst.width()).coerceIn(0f, 1f)
        absY = ((y - dst.top) / dst.height()).coerceIn(0f, 1f)
    }

    private fun goTo(x: Float, y: Float, now: Boolean) {
        toNorm(x, y)
        if (now) {
            main.removeCallbacks(absRun)
            absScheduled = false
            remote.moveTo(absX, absY)
        } else if (!absScheduled) {
            absScheduled = true
            main.postDelayed(absRun, 16)
        }
    }

    private fun addMove(dx: Float, dy: Float) {
        val speed = hypot(dx, dy) / density
        val factor = 1.6f + minOf(speed * 0.4f, 3f)
        accX += dx / density * factor
        accY += dy / density * factor
        val ix = accX.toInt()
        val iy = accY.toInt()
        accX -= ix
        accY -= iy
        pendX += ix
        pendY += iy
        if ((pendX != 0 || pendY != 0) && !flushScheduled) {
            flushScheduled = true
            main.postDelayed(flush, 16)
        }
    }

    private fun finishGesture(e: MotionEvent, cancelled: Boolean) {
        main.removeCallbacks(startDrag)
        main.removeCallbacks(flush)
        main.removeCallbacks(absRun)
        flushScheduled = false
        if (pendX != 0 || pendY != 0) {
            remote.move(pendX, pendY)
            pendX = 0
            pendY = 0
        }
        if (absScheduled) {
            absScheduled = false
            remote.moveTo(absX, absY)
        }
        val dt = e.eventTime - downTime
        if (dragging) {
            remote.mouseButton(1, false)
        } else if (!cancelled) {
            if (twoFinger) {
                if (!scrolled && dt < 350) remote.click(2)
            } else if (!moved && dt < 300) {
                remote.click(1)
            }
        }
        dragging = false
        twoFinger = false
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x; startY = e.y
                lastX = e.x; lastY = e.y
                downTime = e.eventTime
                moved = false
                dragging = false
                twoFinger = false
                scrolled = false
                accX = 0f; accY = 0f
                if (useDirect()) goTo(e.x, e.y, true)
                main.postDelayed(startDrag, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    twoFinger = true
                    main.removeCallbacks(startDrag)
                    lastY = (e.getY(0) + e.getY(1)) / 2f
                    scrollAcc = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val cy = (e.getY(0) + e.getY(1)) / 2f
                    scrollAcc += cy - lastY
                    lastY = cy
                    val step = 28f * density
                    val n = (scrollAcc / step).toInt()
                    if (n != 0) {
                        remote.scroll(n)
                        scrollAcc -= n * step
                        scrolled = true
                    }
                } else if (!twoFinger) {
                    if (!moved && hypot(e.x - startX, e.y - startY) > slop) {
                        moved = true
                        main.removeCallbacks(startDrag)
                    }
                    if (useDirect()) {
                        if (moved || dragging) goTo(e.x, e.y, false)
                    } else {
                        addMove(e.x - lastX, e.y - lastY)
                    }
                    lastX = e.x
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> finishGesture(e, false)
            MotionEvent.ACTION_CANCEL -> finishGesture(e, true)
        }
        return true
    }
}
