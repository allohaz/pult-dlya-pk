package ru.pult.pc

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object Colors {
    val BG = 0xFF101114.toInt()
    val SURFACE = 0xFF1D1F26.toInt()
    val ACCENT = 0xFF7C5CFF.toInt()
    val TEXT = 0xFFEDEDF2.toInt()
    val MUTED = 0xFF8A8D98.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Int = 16): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

fun Context.rippleBg(color: Int, radiusDp: Int = 16): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(color, radiusDp), null)

fun Context.lp(w: Int, h: Int, weight: Float = 0f, m: Int = 4): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(m), dp(m), dp(m), dp(m)) }

fun Context.button(
    label: String,
    sizeSp: Float = 17f,
    color: Int = Colors.SURFACE,
    onClick: () -> Unit
): Button = Button(this).apply {
    text = label
    textSize = sizeSp
    isAllCaps = false
    setTextColor(Colors.TEXT)
    background = rippleBg(color)
    stateListAnimator = null
    setPadding(dp(10), dp(12), dp(10), dp(12))
    setOnClickListener { onClick() }
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
