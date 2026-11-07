package com.rickyboy.appsweep

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Left dial: the ram whose horn is the RAM gauge. Blue = used, green = free, with
 * markings along the horn and a needle from the centre of the curl.
 */
class RamGaugeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val blue = intArrayOf(40, 140, 255)
    private val green = intArrayOf(40, 205, 95)

    private val w = RamArt.RAM_W
    private val h = RamArt.RAM_H
    private val basePx = IntArray(w * h)
    private val outPx: IntArray
    private val tinted: Bitmap
    private val hornIdx: IntArray     // pixel indexes that are horn
    private val hornT: FloatArray     // position along the horn for each of those pixels
    private val hornLum: FloatArray   // brightness of the horn texture there

    private var shown = 0f            // percent drawn now (animates)
    private var tintedFor = -1
    private var animator: ValueAnimator? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val blueText = Color.rgb(30, 120, 240)
    private val greenText = Color.rgb(20, 175, 75)
    private val hornNum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 60f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hornWord = Paint(hornNum).apply { textSize = 30f; letterSpacing = 0.08f }
    private val hornNumOutline = Paint(hornNum).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 11f; strokeJoin = Paint.Join.ROUND
    }
    private val hornWordOutline = Paint(hornWord).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; strokeJoin = Paint.Join.ROUND
    }
    private val needleDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 15, 20) }
    private val needleLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 234, 240) }
    private val bigFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 92f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val smallFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(229, 57, 53); textSize = 34f; letterSpacing = 0.12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val path = Path()

    init {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val base = BitmapFactory.decodeResource(resources, R.drawable.ram_base, opts)
        base.getPixels(basePx, 0, w, 0, 0, w, h)
        val tmap = BitmapFactory.decodeResource(resources, R.drawable.ram_tmap, opts)
        val tPx = IntArray(w * h)
        tmap.getPixels(tPx, 0, w, 0, 0, w, h)
        tmap.recycle()

        val idx = ArrayList<Int>()
        for (i in tPx.indices) if (Color.red(tPx[i]) > 0) idx.add(i)
        hornIdx = idx.toIntArray()
        hornT = FloatArray(hornIdx.size)
        hornLum = FloatArray(hornIdx.size)
        for (k in hornIdx.indices) {
            val i = hornIdx[k]
            hornT[k] = (Color.red(tPx[i]) - 1) / 253f
            val p = basePx[i]
            val lum = (0.3f * Color.red(p) + 0.59f * Color.green(p) + 0.11f * Color.blue(p)) / 180f
            hornLum[k] = lum.coerceIn(0.25f, 1.4f) * 1.05f
        }
        outPx = basePx.copyOf()
        tinted = base.copy(Bitmap.Config.ARGB_8888, true)
        base.recycle()
        retint(0)
    }

    /** 0–100 */
    fun setPercent(value: Float) {
        val target = value.coerceIn(0f, 100f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                val pct = shown.roundToInt()
                if (pct != tintedFor) retint(pct)
                invalidate()
            }
            start()
        }
    }

    private fun boundaryFor(pct: Int) =
        RamArt.SCALE_START + pct / 100f * (RamArt.SCALE_END - RamArt.SCALE_START)

    /** Paints the horn: blue up to the used amount, green after it, natural colour on the thin tip. */
    private fun retint(pct: Int) {
        tintedFor = pct
        val boundary = boundaryFor(pct)
        for (k in hornIdx.indices) {
            val i = hornIdx[k]
            val t = hornT[k]
            if (t > RamArt.SCALE_END) {
                outPx[i] = basePx[i]
                continue
            }
            val c = if (t <= boundary) blue else green
            val l = hornLum[k]
            val a = Color.alpha(basePx[i])
            outPx[i] = Color.argb(
                a,
                (c[0] * l).toInt().coerceIn(0, 255),
                (c[1] * l).toInt().coerceIn(0, 255),
                (c[2] * l).toInt().coerceIn(0, 255)
            )
        }
        tinted.setPixels(outPx, 0, w, 0, 0, w, h)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width * RamArt.DIAL_H / RamArt.DIAL_W)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val s = width.toFloat() / RamArt.DIAL_W
        canvas.save()
        canvas.scale(s, s)

        // Reading in the space above the ram (matches the apps bursting above the other ram)
        // Percentage with "RAM USED" centred underneath it
        val pctText = "${shown.roundToInt()}%"
        val centre = 24f + maxOf(bigFill.measureText(pctText), smallFill.measureText("RAM USED")) / 2f
        bigFill.textAlign = Paint.Align.CENTER
        smallFill.textAlign = Paint.Align.CENTER
        canvas.drawText(pctText, centre, 120f, bigFill)
        canvas.drawText("RAM USED", centre, 168f, smallFill)

        // The ram, sitting at the bottom so its head matches the other ram's size
        canvas.translate(RamArt.RAM_OX, RamArt.RAM_OY)
        canvas.scale(RamArt.RAM_SCALE, RamArt.RAM_SCALE)
        canvas.drawBitmap(tinted, 0f, 0f, bmpPaint)

        val cx = RamArt.RAM_CX
        val cy = RamArt.RAM_CY
        val span = RamArt.SCALE_END - RamArt.SCALE_START

        // "68% USED" on the blue part and "32% FREE" on the green part, outlined in white
        val boundary = RamArt.SCALE_START + shown / 100f * span
        val used = shown.roundToInt()
        drawHornLabel(canvas, cx, cy, (RamArt.SCALE_START + boundary) / 2f, "$used%", "USED", blueText, used >= 6)
        drawHornLabel(canvas, cx, cy, (boundary + RamArt.SCALE_END) / 2f, "${100 - used}%", "FREE", greenText, used <= 94)

        // Needle from the centre of the curl to the used amount
        val ni = RamArt.hornIndex(RamArt.SCALE_START + shown / 100f * span)
        val na = Math.toRadians(RamArt.HORN_ANGLE[ni].toDouble())
        val tipR = RamArt.HORN_R_IN[ni] + 10f
        val baseR = 38f
        val nx = -sin(na).toFloat()
        val ny = cos(na).toFloat()
        drawSpike(canvas, cx, cy, na, tipR, baseR, 10f, nx, ny, needleDark)
        drawSpike(canvas, cx, cy, na, tipR - 5f, baseR, 6.5f, nx, ny, needleLight)

        canvas.restore()
    }

    /** A two-line label ("68%" over "USED") in the middle of the horn at position [t]. */
    private fun drawHornLabel(
        canvas: Canvas, cx: Float, cy: Float, t: Float, num: String, word: String, color: Int, show: Boolean
    ) {
        if (!show) return   // too little of the horn to fit a label
        val i = RamArt.hornIndex(t)
        val a = Math.toRadians(RamArt.HORN_ANGLE[i].toDouble())
        val rr = (RamArt.HORN_R_IN[i] + RamArt.HORN_R_OUT[i]) / 2f
        val x = cx + (rr * cos(a)).toFloat()
        val y = cy + (rr * sin(a)).toFloat()
        hornNum.color = color
        hornWord.color = color
        canvas.drawText(num, x, y + 6f, hornNumOutline)
        canvas.drawText(num, x, y + 6f, hornNum)
        canvas.drawText(word, x, y + 40f, hornWordOutline)
        canvas.drawText(word, x, y + 40f, hornWord)
    }

    private fun drawSpike(
        canvas: Canvas, cx: Float, cy: Float, a: Double, tipR: Float, baseR: Float,
        half: Float, nx: Float, ny: Float, paint: Paint
    ) {
        val bx = cx + (baseR * cos(a)).toFloat()
        val by = cy + (baseR * sin(a)).toFloat()
        path.reset()
        path.moveTo(cx + (tipR * cos(a)).toFloat(), cy + (tipR * sin(a)).toFloat())
        path.lineTo(bx + nx * half, by + ny * half)
        path.lineTo(bx - nx * half, by - ny * half)
        path.close()
        canvas.drawPath(path, paint)
    }
}
