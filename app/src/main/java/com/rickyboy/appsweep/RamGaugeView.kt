package com.rickyboy.appsweep

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Neon speedometer-style dial showing % of RAM in use (matches the SpeedCheck gauges). */
class RamGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cyan = Color.parseColor("#00F0FF")
    private val pink = Color.parseColor("#FF2D95")
    private val purple = Color.parseColor("#9D3CFF")

    private var shown = 0f      // what's drawn now (animates)
    private var animator: ValueAnimator? = null

    private val bezel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B0B16") }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#1C1C2E")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#55FFFFFF") }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan; textAlign = Paint.Align.CENTER; letterSpacing = 0.15f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    }
    private val oval = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // needed for the neon glow
    }

    /** 0–100 */
    fun setPercent(value: Float) {
        val target = value.coerceIn(0f, 100f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w) // always square
    }

    override fun onDraw(canvas: Canvas) {
        val s = width.toFloat()
        val c = s / 2
        val stroke = s * 0.075f

        // Chrome bezel
        bezel.strokeWidth = s * 0.035f
        bezel.shader = LinearGradient(0f, 0f, s, s,
            intArrayOf(Color.parseColor("#F2F2F7"), Color.parseColor("#5A5A66"), Color.parseColor("#D8D8E0"), Color.parseColor("#3A3A44")),
            null, Shader.TileMode.CLAMP)
        val br = c - bezel.strokeWidth / 2
        canvas.drawCircle(c, c, br, face)
        canvas.drawCircle(c, c, br, bezel)

        // Track + ticks
        val r = c - s * 0.16f
        oval.set(c - r, c - r, c + r, c + r)
        track.strokeWidth = stroke
        canvas.drawArc(oval, 135f, 270f, false, track)
        for (i in 0..10) {
            val a = Math.toRadians((135 + i * 27).toDouble())
            val r1 = r - stroke * 1.1f
            val r2 = r1 - s * (if (i % 5 == 0) 0.06f else 0.03f)
            tick.strokeWidth = s * 0.01f
            canvas.drawLine(
                c + (r1 * Math.cos(a)).toFloat(), c + (r1 * Math.sin(a)).toFloat(),
                c + (r2 * Math.cos(a)).toFloat(), c + (r2 * Math.sin(a)).toFloat(), tick
            )
        }

        // Neon value arc: cyan → purple → pink as memory fills up
        arc.strokeWidth = stroke
        val grad = SweepGradient(c, c, intArrayOf(cyan, cyan, purple, pink, pink), floatArrayOf(0f, 0.2f, 0.42f, 0.75f, 1f))
        val m = android.graphics.Matrix().apply { setRotate(135f, c, c) } // start the colours where the dial starts
        grad.setLocalMatrix(m)
        arc.shader = grad
        val glowColor = when {
            shown < 50 -> cyan
            shown < 80 -> purple
            else -> pink
        }
        arc.setShadowLayer(s * 0.05f, 0f, 0f, glowColor)
        if (shown > 0.5f) canvas.drawArc(oval, 135f, 270f * shown / 100f, false, arc)

        // Readout
        big.textSize = s * 0.17f
        big.setShadowLayer(s * 0.04f, 0f, 0f, glowColor)
        canvas.drawText("${shown.toInt()}%", c, c + s * 0.05f, big)
        small.textSize = s * 0.052f
        small.setShadowLayer(s * 0.02f, 0f, 0f, cyan)
        canvas.drawText("RAM USED", c, c + s * 0.16f, small)
    }
}
