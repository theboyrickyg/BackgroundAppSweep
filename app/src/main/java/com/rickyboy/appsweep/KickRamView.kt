package com.rickyboy.appsweep

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * The cleaning screen's moving picture: the ram winds up its back legs, kicks,
 * and the broken apps burst out from its hooves. Loops until the view is removed.
 */
class KickRamView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val body: Bitmap
    private val legs: Bitmap
    private val shards: Bitmap
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shardPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val start = SystemClock.uptimeMillis()

    private companion object {
        const val CYCLE_MS = 1150f   // one kick
        const val WIND_UP = 14f      // degrees the legs pull in before kicking
    }

    init {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        body = BitmapFactory.decodeResource(resources, R.drawable.kick_body, opts)
        legs = BitmapFactory.decodeResource(resources, R.drawable.kick_legs, opts)
        shards = BitmapFactory.decodeResource(resources, R.drawable.kick_shards, opts)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width * RamArt.KICK_H / RamArt.KICK_W)
    }

    private fun easeInOut(x: Float) = x * x * (3 - 2 * x)

    override fun onDraw(canvas: Canvas) {
        val s = width.toFloat() / RamArt.KICK_W
        val u = ((SystemClock.uptimeMillis() - start) % CYCLE_MS.toLong()) / CYCLE_MS  // 0..1 through the kick

        // Legs: slow wind-up (pull in), fast snap out, tiny overshoot
        val legDeg = when {
            u < 0.45f -> WIND_UP * easeInOut(u / 0.45f)
            u < 0.52f -> WIND_UP * (1f - (u - 0.45f) / 0.07f)
            u < 0.60f -> -3f * (1f - (u - 0.52f) / 0.08f)
            else -> 0f
        }
        // The whole ram jolts forward with each kick
        val jolt = if (u in 0.50f..0.75f) -7f * (1f - (u - 0.50f) / 0.25f) else 0f

        canvas.save()
        canvas.scale(s, s)

        // Broken apps burst out from the hooves at the moment of the kick, then scatter and fade
        if (u >= 0.50f) {
            val k = (u - 0.50f) / 0.50f
            val scale = 0.65f + 0.6f * k
            val alpha = if (k < 0.18f) k / 0.18f else 1f - (k - 0.18f) / 0.82f
            shardPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.save()
            canvas.scale(scale, scale, RamArt.KICK_HOOF_X, RamArt.KICK_HOOF_Y)
            canvas.drawBitmap(shards, 0f, 0f, shardPaint)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(jolt, 0f)
        // Legs behind the body, swinging from the hip
        canvas.save()
        canvas.rotate(legDeg, RamArt.KICK_HIP_X, RamArt.KICK_HIP_Y)
        canvas.drawBitmap(legs, 0f, 0f, paint)
        canvas.restore()
        canvas.drawBitmap(body, 0f, 0f, paint)
        canvas.restore()

        canvas.restore()
        if (isShown) postInvalidateOnAnimation()
    }
}
