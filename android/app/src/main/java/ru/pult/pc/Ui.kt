package ru.pult.pc

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object Colors {
    val BG = 0xFF0E0F13.toInt()
    val SURFACE = 0xFF1B1D25.toInt()
    val SURFACE2 = 0xFF252833.toInt()
    val ACCENT = 0xFF7C5CFF.toInt()
    val ACCENT2 = 0xFF4C8DFF.toInt()
    val TEXT = 0xFFEDEDF2.toInt()
    val MUTED = 0xFF8A8D98.toInt()
    val OK = 0xFF3DDC84.toInt()
    val WARN = 0xFFFFB020.toInt()
    val DANGER = 0xFFFF6B6B.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Скруглённый фон. Акцентный цвет рисуется градиентом. */
fun Context.rounded(color: Int, radiusDp: Int = 16): GradientDrawable {
    val d = if (color == Colors.ACCENT) {
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Colors.ACCENT, Colors.ACCENT2))
    } else {
        GradientDrawable().apply { setColor(color) }
    }
    d.cornerRadius = dp(radiusDp).toFloat()
    return d
}

fun Context.rippleBg(color: Int, radiusDp: Int = 16): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(color, radiusDp), null)

fun Context.lp(w: Int, h: Int, weight: Float = 0f, m: Int = 4): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(m), dp(m), dp(m), dp(m)) }

fun Context.button(
    label: String,
    sizeSp: Float = 17f,
    color: Int = Colors.SURFACE,
    textColor: Int = Colors.TEXT,
    onClick: () -> Unit
): Button = Button(this).apply {
    text = label
    textSize = sizeSp
    isAllCaps = false
    setTextColor(textColor)
    background = rippleBg(color)
    stateListAnimator = null
    setPadding(dp(10), dp(12), dp(10), dp(12))
    setOnClickListener { onClick() }
    // лёгкое «нажатие» кнопки
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        false
    }
}

fun Context.label(text: String, sizeSp: Float = 16f, color: Int = Colors.TEXT): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
    }

fun Context.field(hint: String, numeric: Boolean = false): EditText = EditText(this).apply {
    this.hint = hint
    setHintTextColor(Colors.MUTED)
    setTextColor(Colors.TEXT)
    textSize = 18f
    isSingleLine = true
    background = rounded(Colors.SURFACE)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    gravity = Gravity.CENTER_VERTICAL
    if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
}

/** Горизонтальный ряд кнопок одинаковой ширины. */
fun Context.row(vararg views: View, heightWeight: Float = 0f): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, lp(0, if (heightWeight > 0f) -1 else -2, 1f, 4))
    }.also {
        it.layoutParams = lp(-1, if (heightWeight > 0f) 0 else -2, heightWeight, 0)
    }

fun View.fadeIn() {
    alpha = 0f
    animate().alpha(1f).setDuration(180).start()
}
