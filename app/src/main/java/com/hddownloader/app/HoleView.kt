package com.hddownloader.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Home screen — final design (Shani ki pasand):
 * safed background, upar blue banner (S logo tile + EXPERT SOLUTIONS DOWNLOADER
 * capital bold), beech me mix-green gola, gird banner se match blue rings.
 * Koi extra icon/text nahi. Animation sirf tab jab screen nazar aa rahi ho
 * (battery bachane ke liye).
 */
class HoleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { IDLE, PROCESSING, DOWNLOADING, DONE, ERROR }

    interface Listener {
        fun onHoleTap()
    }

    var listener: Listener? = null

    var state: State = State.IDLE
        set(v) {
            field = v
            invalidate()
        }

    var percent: Int = 0
        set(v) {
            field = v.coerceIn(0, 100)
            invalidate()
        }

    var hintText: String = "Copy a video link, then tap"
        set(v) {
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // banner / brand colors (logo ki scheme)
    private val blueLight = Color.rgb(66, 178, 232)
    private val blueMid = Color.rgb(27, 111, 208)
    private val blueDeep = Color.rgb(10, 61, 145)
    // mix green gola
    private val greenHi = Color.rgb(205, 243, 160)
    private val greenMid = Color.rgb(88, 181, 49)
    private val greenDark = Color.rgb(16, 84, 27)

    private val logoMark: Bitmap? by lazy {
        try {
            BitmapFactory.decodeResource(resources, R.drawable.logo_mark)
        } catch (_: Exception) {
            null
        }
    }

    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = Color.WHITE
    }
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(dp(16f), 0f, dp(9f), Color.argb(85, 20, 70, 110))
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = blueMid
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = blueMid
        alpha = 38
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = blueMid
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        setShadowLayer(dp(7f), 0f, dp(2f), Color.argb(120, 10, 60, 20))
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.rgb(90, 101, 112)
    }

    private var animating = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!animating) return
            invalidate()
            postDelayed(this, 50)
        }
    }

    init {
        // shadows + dash effects har phone par sahi chalein
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startAnim() else stopAnim()
    }

    private fun startAnim() {
        if (!animating) {
            animating = true
            post(ticker)
        }
    }

    private fun stopAnim() {
        animating = false
        removeCallbacks(ticker)
    }

    private fun sphereCx(w: Int) = w / 2f
    private fun sphereCy(h: Int) = h * 0.53f
    private fun sphereR(w: Int) = w * 0.293f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val t = SystemClock.uptimeMillis() / 1000f

        canvas.drawColor(Color.WHITE)
        drawBanner(canvas, w)

        val cx = sphereCx(w)
        val cy = sphereCy(h)
        val r = sphereR(w)

        // ---- dashed rotating ring (blue, banner se match)
        val rot = (t * 14f) % 360f
        dashPaint.strokeWidth = dp(4.5f)
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(8f)), 0f)
        canvas.save()
        canvas.rotate(rot, cx, cy)
        canvas.drawCircle(cx, cy, r + dp(12f), dashPaint)
        canvas.restore()

        // ---- bahari arcs + progress
        val arcRect = RectF(cx - r - dp(23f), cy - r - dp(23f), cx + r + dp(23f), cy + r + dp(23f))
        if (state == State.IDLE) {
            val base = (t * 22f) % 360f
            arcPaint.strokeWidth = dp(7f)
            arcPaint.color = blueMid
            canvas.drawArc(arcRect, base, 100f, false, arcPaint)
            arcPaint.color = blueDeep
            canvas.drawArc(arcRect, base + 150f, 55f, false, arcPaint)
            arcPaint.color = blueLight
            canvas.drawArc(arcRect, base + 245f, 40f, false, arcPaint)
        } else {
            trackPaint.strokeWidth = dp(7f)
            canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
            val base = (t * 22f) % 360f
            arcPaint.strokeWidth = dp(7f)
            arcPaint.color = blueLight
            arcPaint.alpha = 110
            canvas.drawArc(arcRect, base, 60f, false, arcPaint)
            arcPaint.alpha = 255
            progressPaint.strokeWidth = dp(8.5f)
            val sweep = percent * 3.6f
            if (sweep > 0f) canvas.drawArc(arcRect, -90f, sweep, false, progressPaint)
        }

        // ---- mix green gola (upar light, kinare dark)
        spherePaint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.35f, r * 1.5f,
            intArrayOf(greenHi, greenMid, greenDark),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, spherePaint)
        spherePaint.shader = null

        drawCenterText(canvas, cx, cy, r)

        // ---- status / hint line gole ke neeche
        var size = dp(14.5f)
        if (hintText.length > 44) size = dp(12.5f)
        hintPaint.textSize = size
        canvas.drawText(hintText, w / 2f, cy + r + dp(56f), hintPaint)
    }

    private fun drawBanner(canvas: Canvas, w: Int) {
        val mx = dp(10f)
        val top = dp(22f)
        val bh = dp(112f)
        val rect = RectF(mx, top, w - mx, top + bh)
        bannerPaint.shader = LinearGradient(
            rect.left, 0f, rect.right, 0f,
            intArrayOf(blueMid, blueDeep), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, dp(16f), dp(16f), bannerPaint)
        bannerPaint.shader = null

        // white tile + S logo
        val tile = dp(80f)
        val tx = rect.left + dp(12f)
        val ty = rect.top + (bh - tile) / 2f
        val tileRect = RectF(tx, ty, tx + tile, ty + tile)
        canvas.drawRoundRect(tileRect, dp(14f), dp(14f), tilePaint)
        logoMark?.let { bmp ->
            val pad = dp(7f)
            val dest = RectF(
                tileRect.left + pad, tileRect.top + pad,
                tileRect.right - pad, tileRect.bottom - pad
            )
            val clip = Path().apply { addRoundRect(tileRect, dp(14f), dp(14f), Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(bmp, null, dest, tilePaint)
            canvas.restore()
        }

        // naam: EXPERT SOLUTIONS / DOWNLOADER — capital, bold, saaf
        val textX = tileRect.right + dp(13f)
        val avail = rect.right - dp(10f) - textX
        var s1 = dp(21f)
        bannerTextPaint.letterSpacing = 0f
        while (s1 > dp(10f)) {
            bannerTextPaint.textSize = s1
            if (bannerTextPaint.measureText("EXPERT SOLUTIONS") <= avail) break
            s1 -= dp(1f)
        }
        val s2 = s1 * 0.86f
        val lineGap = dp(7f)
        val totalH = s1 + lineGap + s2
        var baseline = rect.top + (bh - totalH) / 2f + s1 * 0.82f
        bannerTextPaint.textSize = s1
        canvas.drawText("EXPERT SOLUTIONS", textX, baseline, bannerTextPaint)
        bannerTextPaint.textSize = s2
        bannerTextPaint.letterSpacing = 0.22f
        canvas.drawText("DOWNLOADER", textX, baseline + s2 + lineGap, bannerTextPaint)
        bannerTextPaint.letterSpacing = 0f
    }

    private fun drawCenterText(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fun text(txt: String, size: Float, dy: Float, paint: Paint) {
            paint.textSize = size
            val fm = paint.fontMetrics
            canvas.drawText(txt, cx, cy + dy - (fm.ascent + fm.descent) / 2f, paint)
        }
        when (state) {
            State.IDLE -> text("CLICK HERE", r * 0.30f, 0f, centerPaint)
            State.PROCESSING -> {
                text("$percent%", r * 0.56f, -r * 0.10f, centerPaint)
                text("Processing…", r * 0.17f, r * 0.34f, subPaint)
            }
            State.DOWNLOADING -> {
                text("DOWNLOAD", r * 0.25f, -r * 0.30f, centerPaint)
                text("$percent%", r * 0.52f, r * 0.22f, centerPaint)
            }
            State.DONE -> {
                text("✓", r * 0.58f, -r * 0.16f, centerPaint)
                text("DONE", r * 0.28f, r * 0.38f, centerPaint)
            }
            State.ERROR -> {
                text("✕", r * 0.48f, -r * 0.14f, centerPaint)
                text("FAILED", r * 0.24f, r * 0.36f, centerPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val dx = event.x - sphereCx(width)
            val dy = event.y - sphereCy(height)
            if (Math.hypot(dx.toDouble(), dy.toDouble()) < sphereR(width) + dp(34f)) {
                performClick()
                listener?.onHoleTap()
                return true
            }
            return super.onTouchEvent(event)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
