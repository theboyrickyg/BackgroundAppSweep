package com.rickyboy.appsweep

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Second dial: shows cleaning progress, with a brush sweeping in the middle while it works.
 * Same chrome bezel and neon style as the RAM dial.
 */
class CleanGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Mode { IDLE, CLEANING, DONE }

    private val cyan = Color.parseColor("#00F0FF")
    private val green = Color.parseColor("#00E676")
    private val red = Color.parseColor("#E53935")
    private val darkRed = Color.parseColor("#9E1B1B")

    private var mode = Mode.IDLE
    private var shown = 0f                 // progress drawn now (animates)
    private var sweepPhase = 0.5f          // 0..1 brush swing position
    private var progressAnim: ValueAnimator? = null
    private val swingAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 420
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { sweepPhase = it.animatedValue as Float; invalidate() }
    }

    private val bezel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B0B16") }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#1C1C2E")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dust = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; letterSpacing = 0.15f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    }
    private val oval = RectF()
    private val path = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // needed for the neon glow
    }

    // ---------- Controls ----------

    fun startCleaning() {
        mode = Mode.CLEANING
        progressAnim?.cancel()
        shown = 0f
        if (!swingAnim.isStarted) swingAnim.start()
        invalidate()
    }

    /** 0–100 while cleaning */
    fun setProgress(value: Float) {
        val target = value.coerceIn(0f, 100f)
        progressAnim?.cancel()
        progressAnim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun finish() {
        setProgress(100f)
        mode = Mode.DONE
        swingAnim.cancel()
        sweepPhase = 0.5f
        invalidate()
    }

    fun reset() {
        mode = Mode.IDLE
        progressAnim?.cancel()
        shown = 0f
        swingAnim.cancel()
        sweepPhase = 0.5f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        swingAnim.cancel()
        progressAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w) // always square
    }

    // ---------- Drawing ----------

    override fun onDraw(canvas: Canvas) {
        val s = width.toFloat()
        val c = s / 2
        val stroke = s * 0.075f

        // Chrome bezel + face (matches the RAM dial)
        bezel.strokeWidth = s * 0.035f
        bezel.shader = LinearGradient(0f, 0f, s, s,
            intArrayOf(Color.parseColor("#F2F2F7"), Color.parseColor("#5A5A66"), Color.parseColor("#D8D8E0"), Color.parseColor("#3A3A44")),
            null, Shader.TileMode.CLAMP)
        val br = c - bezel.strokeWidth / 2
        canvas.drawCircle(c, c, br, face)
        canvas.drawCircle(c, c, br, bezel)

        // Track
        val r = c - s * 0.16f
        oval.set(c - r, c - r, c + r, c + r)
        track.strokeWidth = stroke
        canvas.drawArc(oval, 135f, 270f, false, track)

        // Progress arc: cyan → green
        val glow = if (mode == Mode.DONE) green else cyan
        arc.strokeWidth = stroke
        arc.shader = SweepGradient(c, c, intArrayOf(cyan, cyan, green, green), floatArrayOf(0f, 0.2f, 0.7f, 1f))
            .apply { setLocalMatrix(Matrix().apply { setRotate(135f, c, c) }) }
        arc.setShadowLayer(s * 0.05f, 0f, 0f, glow)
        if (shown > 0.5f) canvas.drawArc(oval, 135f, 270f * shown / 100f, false, arc)

        // Dust flying off the brush while it works
        if (mode == Mode.CLEANING) drawDust(canvas, c, s)

        // The brush
        val swing = (sweepPhase - 0.5f) // -0.5..0.5
        val angle = 35f + swing * 40f
        val dx = swing * s * 0.10f
        drawBrush(canvas, c + dx, c - s * 0.05f, s * 0.40f, angle)

        // Readout
        val label = when (mode) {
            Mode.IDLE -> "READY"
            Mode.CLEANING -> "${shown.toInt()}%"
            Mode.DONE -> "CLEAN ✓"
        }
        big.textSize = if (mode == Mode.CLEANING) s * 0.12f else s * 0.085f
        big.setShadowLayer(s * 0.03f, 0f, 0f, glow)
        canvas.drawText(label, c, c + s * 0.27f, big)
        small.textSize = s * 0.048f
        small.color = glow
        small.setShadowLayer(s * 0.02f, 0f, 0f, glow)
        canvas.drawText("CLEANING", c, c + s * 0.36f, small)
    }

    /**
     * Wide flat paint brush (decorator's brush): red handle, silver metal band, wide cream bristles.
     * Drawn pointing down, then tilted. Only used inside the dial; the app icon is separate.
     */
    private fun drawBrush(canvas: Canvas, cx: Float, cy: Float, len: Float, angle: Float) {
        canvas.save()
        canvas.rotate(angle, cx, cy)
        val handleTop = cy - len * 0.58f
        val handleBottom = cy - len * 0.02f
        val bandTop = handleBottom
        val bandBottom = cy + len * 0.12f
        val bristleBottom = cy + len * 0.42f
        val hwTop = len * 0.045f      // handle half-width at the end
        val hwNeck = len * 0.075f     // handle half-width where it meets the band
        val bw = len * 0.21f          // half-width of band and bristles (wide brush)

        // Handle: tapered, red, with a hanging hole
        path.reset()
        path.moveTo(cx - hwTop, handleTop + hwTop)
        path.quadTo(cx, handleTop - hwTop * 0.6f, cx + hwTop, handleTop + hwTop)
        path.lineTo(cx + hwNeck, handleBottom - len * 0.06f)
        path.lineTo(cx + hwNeck * 1.6f, handleBottom)
        path.lineTo(cx - hwNeck * 1.6f, handleBottom)
        path.lineTo(cx - hwNeck, handleBottom - len * 0.06f)
        path.close()
        fill.shader = LinearGradient(cx - hwNeck, 0f, cx + hwNeck, 0f, red, darkRed, Shader.TileMode.CLAMP)
        fill.setShadowLayer(len * 0.04f, 0f, len * 0.01f, Color.argb(120, 0, 0, 0))
        canvas.drawPath(path, fill)
        fill.clearShadowLayer()
        fill.shader = null
        fill.color = Color.parseColor("#0B0B16")
        canvas.drawCircle(cx, handleTop + hwTop * 1.6f, hwTop * 0.5f, fill)

        // Metal band (ferrule) with crimp lines
        fill.shader = LinearGradient(cx - bw, 0f, cx + bw, 0f,
            intArrayOf(Color.parseColor("#8A8F99"), Color.parseColor("#F2F4F7"), Color.parseColor("#A4AAB4"), Color.parseColor("#6E737C")),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(cx - bw, bandTop, cx + bw, bandBottom), len * 0.02f, len * 0.02f, fill)
        fill.shader = null
        line.color = Color.parseColor("#5C616A")
        line.strokeWidth = len * 0.012f
        for (k in 1..2) {
            val y = bandTop + (bandBottom - bandTop) * k / 3f
            canvas.drawLine(cx - bw * 0.92f, y, cx + bw * 0.92f, y, line)
        }

        // Wide flat bristles, slightly flared, with a soft rounded tip
        path.reset()
        path.moveTo(cx - bw * 0.96f, bandBottom)
        path.lineTo(cx + bw * 0.96f, bandBottom)
        path.lineTo(cx + bw * 1.06f, bristleBottom - len * 0.05f)
        path.quadTo(cx, bristleBottom + len * 0.03f, cx - bw * 1.06f, bristleBottom - len * 0.05f)
        path.close()
        fill.shader = LinearGradient(0f, bandBottom, 0f, bristleBottom,
            Color.parseColor("#FFF6E3"), Color.parseColor("#E9D9B8"), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fill)
        fill.shader = null
        line.color = Color.parseColor("#C9B48C")
        line.strokeWidth = len * 0.008f
        for (i in -6..6) {
            val t = i / 6f
            canvas.drawLine(cx + t * bw * 0.88f, bandBottom + len * 0.02f,
                cx + t * bw * 0.98f, bristleBottom - len * 0.04f, line)
        }
        canvas.restore()
    }

    private fun drawDust(canvas: Canvas, c: Float, s: Float) {
        val now = SystemClock.uptimeMillis()
        for (i in 0 until 9) {
            val t = ((now / 900f) + i / 9f) % 1f          // 0..1 life of each speck
            val a = Math.toRadians((110 + i * 17).toDouble())
            val dist = s * (0.10f + t * 0.22f)
            val x = c - s * 0.08f + (dist * cos(a)).toFloat()
            val y = c + s * 0.12f + (dist * sin(a)).toFloat() * 0.6f
            dust.color = if (i % 3 == 0) red else Color.argb(255, 140, 230, 255)
            dust.alpha = ((1f - t) * 220).toInt()
            canvas.drawCircle(x, y, s * 0.012f * (1.2f - t * 0.5f), dust)
        }
    }
}
