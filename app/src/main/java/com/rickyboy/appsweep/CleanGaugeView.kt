package com.rickyboy.appsweep

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Right dial: the ram with its skull lid open and apps bursting out.
 *  - Ready: the apps float gently.
 *  - Cleaning: they shake, drift away and fade as the progress goes up.
 *  - Done: they're gone. "CLEAN ✓".
 * The horn is left exactly as drawn.
 */
class CleanGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Mode { IDLE, CLEANING, DONE }

    private val red = Color.parseColor("#E53935")
    private val green = Color.parseColor("#00E676")

    private val ram: Bitmap        // ram 5's head, open for the apps to burst out
    private val burst: Bitmap
    private val doneHead: Bitmap   // the tidy head shown once it's all clean
    private var mode = Mode.IDLE
    private var shown = 0f              // progress 0–100 (animates)
    private var burstAlpha = 1f         // 0–1
    private var progressAnim: ValueAnimator? = null
    private var alphaAnim: ValueAnimator? = null

    private val burstPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val headPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bigFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 70f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val smallFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 28f; letterSpacing = 0.12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        ram = BitmapFactory.decodeResource(resources, R.drawable.clean_ram, opts)
        burst = BitmapFactory.decodeResource(resources, R.drawable.clean_burst, opts)
        doneHead = BitmapFactory.decodeResource(resources, R.drawable.clean_done, opts)
    }

    // ---------- Controls (same as before, so the rest of the app is unchanged) ----------

    fun startCleaning() {
        mode = Mode.CLEANING
        progressAnim?.cancel()
        alphaAnim?.cancel()
        burstAlpha = 1f
        shown = 0f
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
        fadeBurstTo(0f, 450)
    }

    fun reset() {
        mode = Mode.IDLE
        progressAnim?.cancel()
        shown = 0f
        fadeBurstTo(1f, 700)
    }

    private fun fadeBurstTo(target: Float, ms: Long) {
        alphaAnim?.cancel()
        alphaAnim = ValueAnimator.ofFloat(burstAlpha, target).apply {
            duration = ms
            addUpdateListener { burstAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        progressAnim?.cancel()
        alphaAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width * RamArt.CLEAN_H / RamArt.CLEAN_W)
    }

    // ---------- Drawing ----------

    override fun onDraw(canvas: Canvas) {
        val s = width.toFloat() / RamArt.CLEAN_W
        canvas.save()
        canvas.scale(s, s)

        // As the apps fade out at the end, crossfade to the tidy head (and back when it resets)
        val tidy = (1f - burstAlpha).coerceIn(0f, 1f)
        if (tidy < 0.99f) {
            headPaint.alpha = ((1f - tidy) * 255).toInt()
            canvas.drawBitmap(ram, 0f, 0f, headPaint)
        }
        if (tidy > 0.01f) {
            headPaint.alpha = (tidy * 255).toInt()
            canvas.drawBitmap(doneHead, 0f, 0f, headPaint)
        }

        // The apps bursting out of its head
        val now = SystemClock.uptimeMillis()
        val p = shown / 100f
        var alpha = burstAlpha
        canvas.save()
        when (mode) {
            Mode.IDLE -> {
                val bob = sin(now / 2400.0 * 2 * Math.PI).toFloat() * 5f
                canvas.translate(0f, bob)
            }
            Mode.CLEANING -> {
                // Shake, then drift up and away while fading as the clean goes on
                val jx = (Random.nextFloat() - 0.5f) * 7f
                val jy = (Random.nextFloat() - 0.5f) * 7f
                val grow = 1f + 0.35f * p
                canvas.translate(jx + 30f * p, jy - 40f * p)
                canvas.scale(grow, grow, RamArt.CLEAN_BURST_X, RamArt.CLEAN_BURST_Y)
                alpha *= (1f - 0.85f * p)
            }
            Mode.DONE -> {
                canvas.translate(30f, -40f)
                canvas.scale(1.35f, 1.35f, RamArt.CLEAN_BURST_X, RamArt.CLEAN_BURST_Y)
            }
        }
        if (alpha > 0.01f) {
            burstPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(burst, 0f, 0f, burstPaint)
        }
        canvas.restore()

        // Reading, bottom-right (the ram faces left, so this corner is clear)
        val (big, small, glow) = when (mode) {
            Mode.IDLE -> Triple("READY", "TO CLEAN", red)
            Mode.CLEANING -> Triple("${shown.roundToInt()}%", "CLEANING", red)
            Mode.DONE -> Triple("CLEAN", "COMPLETE", green)
        }
        bigFill.textSize = if (mode == Mode.CLEANING) 72f else 58f
        bigFill.setShadowLayer(10f, 0f, 2f, Color.BLACK)   // readable over the horn
        smallFill.color = glow
        smallFill.setShadowLayer(6f, 0f, 1f, Color.BLACK)
        // Big word kept to the bottom-right corner, small word centred underneath it
        val right = RamArt.CLEAN_W - 16f
        val centre = right - bigFill.measureText(big) / 2f
        canvas.drawText(big, centre, RamArt.CLEAN_H - 44f, bigFill)
        canvas.drawText(small, centre, RamArt.CLEAN_H - 12f, smallFill)
        canvas.restore()

        // keep the apps moving: smooth while cleaning, a gentle 25 fps bob while idle
        when (mode) {
            Mode.CLEANING -> postInvalidateOnAnimation()
            Mode.IDLE -> if (isShown) postInvalidateDelayed(40)
            Mode.DONE -> Unit
        }
    }
}
