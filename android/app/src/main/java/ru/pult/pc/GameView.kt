package ru.pult.pc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Игровая раскладка на весь экран (горизонтально):
 *  - слева: стик (WASD) — касание в левой части, куда угодно
 *  - справа: камера — двигай пальцем по свободному месту
 *  - кнопки: огонь, прицел, прыжок, присесть, бег, R, E, F, Q, 1–4, Tab, Esc, выход
 * Работает с несколькими пальцами сразу. Картинку игры смотри на мониторе.
 */
class GameView(
    context: Context,
    private val remote: Remote,
    private val onExit: () -> Unit
) : View(context) {

    var sens = 1f

    private class Btn(
        val id: String,
        val label: String,
        val x: Float,          // dp от края
        val y: Float,          // dp от края
        val r: Float,          // радиус, dp
        val right: Boolean,    // отсчёт x от правого края (иначе от левого)
        val top: Boolean,      // отсчёт y от верха (иначе от низа)
        val key: String? = null,
        val mouse: Int = 0
    ) {
        var cx = 0f
        var cy = 0f
        var rr = 0f
    }

    private class Role(val type: Int, val btn: Btn?) {
        var ox = 0f
        var oy = 0f
        var lx = 0f
        var ly = 0f
    }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val main = Handler(Looper.getMainLooper())

    private val buttons = listOf(
        Btn("fire", "Огонь", 92f, 92f, 46f, right = true, top = false, mouse = 1),
        Btn("aim", "Цель", 200f, 62f, 32f, right = true, top = false, mouse = 2),
        Btn("jump", "Прыжок", 60f, 196f, 34f, right = true, top = false, key = "space"),
        Btn("crouch", "Присесть", 166f, 166f, 30f, right = true, top = false, key = "ctrl"),
        Btn("reload", "R", 258f, 112f, 28f, right = true, top = false, key = "r"),
        Btn("use", "E", 258f, 196f, 28f, right = true, top = false, key = "e"),
        Btn("f", "F", 344f, 64f, 26f, right = true, top = false, key = "f"),
        Btn("q", "Q", 344f, 150f, 26f, right = true, top = false, key = "q"),
        Btn("sprint", "Бег", 230f, 70f, 30f, right = false, top = false, key = "shift"),
        Btn("tab", "Tab", 60f, 36f, 24f, right = false, top = true, key = "tab"),
        Btn("esc", "Esc", 150f, 36f, 24f, right = true, top = true, key = "esc"),
        Btn("exit", "✕", 50f, 36f, 24f, right = true, top = true),
        Btn("k1", "1", 0f, 0f, 22f, right = false, top = true, key = "1"),
        Btn("k2", "2", 0f, 0f, 22f, right = false, top = true, key = "2"),
        Btn("k3", "3", 0f, 0f, 22f, right = false, top = true, key = "3"),
        Btn("k4", "4", 0f, 0f, 22f, right = false, top = true, key = "4")
    )

    private val roles = HashMap<Int, Role>()
    private val pressed = HashSet<String>()          // нажатые кнопки (для подсветки)
    private val heldKeys = HashSet<String>()
    private val heldMouse = HashSet<Int>()
    private val stickKeys = HashSet<String>()
    private var stickRole: Role? = null
    private var knobX = 0f
    private var knobY = 0f

    private var accX = 0f
    private var accY = 0f
    private var pendX = 0
    private var pendY = 0
    private var flushScheduled = false
    private val flush = Runnable {
        flushScheduled = false
        if (pendX != 0 || pendY != 0) {
            remote.look(pendX, pendY)
            pendX = 0
            pendY = 0
        }
    }

    private fun layoutButtons() {
        val w = width.toFloat()
        val h = height.toFloat()
        var weaponIndex = 0
        for (b in buttons) {
            if (b.id.length == 2 && b.id[0] == 'k') {
                // цифры оружия — по центру сверху
                b.cx = w / 2f + (weaponIndex - 1.5f) * 56f * d
                b.cy = 36f * d
                weaponIndex++
            } else {
                b.cx = if (b.right) w - b.x * d else b.x * d
                b.cy = if (b.top) b.y * d else h - b.y * d
            }
            b.rr = b.r * d
        }
    }

    private fun hit(x: Float, y: Float): Btn? {
        for (b in buttons) {
            if (hypot(x - b.cx, y - b.cy) <= b.rr + 6 * d) return b
        }
        return null
    }

    private fun press(b: Btn) {
        pressed.add(b.id)
        if (b.id == "exit") {
            onExit()
            return
        }
        val k = b.key
        if (k != null) {
            heldKeys.add(k)
            remote.keyHold(k, true)
        } else if (b.mouse != 0) {
            heldMouse.add(b.mouse)
            remote.gameButton(b.mouse, true)
        }
    }

    private fun release(b: Btn) {
        pressed.remove(b.id)
        val k = b.key
        if (k != null) {
            if (heldKeys.remove(k)) remote.keyHold(k, false)
        } else if (b.mouse != 0) {
            if (heldMouse.remove(b.mouse)) remote.gameButton(b.mouse, false)
        }
    }

    private fun setStick(dx: Float, dy: Float) {
        val dead = 16 * d
        val want = HashSet<String>()
        if (dy < -dead) want.add("w")
        if (dy > dead) want.add("s")
        if (dx < -dead) want.add("a")
        if (dx > dead) want.add("d")
        for (k in stickKeys.toList()) {
            if (k !in want) {
                stickKeys.remove(k)
                remote.keyHold(k, false)
            }
        }
        for (k in want) {
            if (stickKeys.add(k)) remote.keyHold(k, true)
        }
    }

    private fun stopStick() {
        for (k in stickKeys.toList()) remote.keyHold(k, false)
        stickKeys.clear()
        stickRole = null
    }

    private fun addLook(dx: Float, dy: Float) {
        accX += dx / d * 2.2f * sens
        accY += dy / d * 2.2f * sens
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

    /** Отпустить всё, что зажато (при выходе из режима или потере связи). */
    fun releaseAll() {
        main.removeCallbacks(flush)
        flushScheduled = false
        stopStick()
        for (k in heldKeys.toList()) remote.keyHold(k, false)
        for (m in heldMouse.toList()) remote.gameButton(m, false)
        heldKeys.clear()
        heldMouse.clear()
        pressed.clear()
        roles.clear()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                val b = hit(x, y)
                if (b != null) {
                    roles[id] = Role(0, b)
                    press(b)
                } else if (x < width * 0.45f && stickRole == null) {
                    val r = Role(1, null)
                    r.ox = x; r.oy = y
                    knobX = x; knobY = y
                    roles[id] = r
                    stickRole = r
                } else {
                    val r = Role(2, null)
                    r.lx = x; r.ly = y
                    roles[id] = r
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val role = roles[e.getPointerId(i)] ?: continue
                    val x = e.getX(i)
                    val y = e.getY(i)
                    when (role.type) {
                        1 -> {
                            var dx = x - role.ox
                            var dy = y - role.oy
                            val len = hypot(dx, dy)
                            val maxLen = 64 * d
                            if (len > maxLen) {
                                dx = dx / len * maxLen
                                dy = dy / len * maxLen
                            }
                            knobX = role.ox + dx
                            knobY = role.oy + dy
                            setStick(dx, dy)
                        }
                        2 -> {
                            addLook(x - role.lx, y - role.ly)
                            role.lx = x
                            role.ly = y
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val role = roles.remove(id)
                if (role != null) {
                    when (role.type) {
                        0 -> role.btn?.let { release(it) }
                        1 -> stopStick()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        layoutButtons()
        p.style = Paint.Style.FILL
        p.color = Colors.BG
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)

        // подсказки
        p.color = Colors.MUTED
        p.textSize = 13 * d
        p.textAlign = Paint.Align.CENTER
        c.drawText("слева — движение", width * 0.22f, height * 0.5f, p)
        c.drawText("свободное место справа — камера", width * 0.66f, height * 0.5f, p)

        // стик
        val s = stickRole
        if (s != null) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3 * d
            p.color = Colors.SURFACE2
            c.drawCircle(s.ox, s.oy, 64 * d, p)
            p.style = Paint.Style.FILL
            p.color = Colors.ACCENT
            c.drawCircle(knobX, knobY, 26 * d, p)
        }

        // кнопки
        for (b in buttons) {
            p.style = Paint.Style.FILL
            p.color = if (b.id in pressed) Colors.ACCENT else Colors.SURFACE
            c.drawCircle(b.cx, b.cy, b.rr, p)
            p.color = Colors.TEXT
            p.textAlign = Paint.Align.CENTER
            p.textSize = (if (b.rr > 40 * d) 15 else 12) * d
            c.drawText(b.label, b.cx, b.cy + p.textSize / 3f, p)
        }
    }
}
