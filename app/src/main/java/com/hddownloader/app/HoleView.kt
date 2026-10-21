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
    // gola: neela -> teal -> hara (reference design wala)
    private val ballBlue = Color.rgb(31, 160, 240)
    private val ballTeal = Color.rgb(23, 196, 178)
    private val ballGreen = Color.rgb(74, 205, 60)
    private val greenArc = Color.rgb(92, 198, 60)
    private val greenWave = Color.rgb(66, 186, 74)
    private val greenBright = Color.rgb(64, 224, 106)

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
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(10f), 0f, dp(4f), Color.argb(60, 30, 80, 120))
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 101, 112)
    }
    private val pillBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = blueMid
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
        drawBackdrop(canvas, w, h)
        drawBanner(canvas, w)

        val cx = sphereCx(w)
        val cy = sphereCy(h)
        val r = sphereR(w)

        // ---- bahari arcs + progress
        val arcRect = RectF(cx - r - dp(23f), cy - r - dp(23f), cx + r + dp(23f), cy + r + dp(23f))
        trackPaint.strokeWidth = dp(7f)
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
        if (state == State.IDLE) {
            val base = (t * 22f) % 360f
            arcPaint.strokeWidth = dp(7.5f)
            arcPaint.color = blueMid
            canvas.drawArc(arcRect, base, 95f, false, arcPaint)
            arcPaint.color = greenArc
            canvas.drawArc(arcRect, base + 125f, 70f, false, arcPaint)
            arcPaint.color = blueDeep
            canvas.drawArc(arcRect, base + 225f, 50f, false, arcPaint)
            arcPaint.color = blueLight
            canvas.drawArc(arcRect, base + 300f, 32f, false, arcPaint)
        } else {
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

        // ---- gola: neela (left/upar) se hara (right/neeche), glossy
        spherePaint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(ballBlue, ballTeal, ballGreen),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, spherePaint)
        spherePaint.shader = null
        // glossy chamak (upar-left)
        glossPaint.shader = RadialGradient(
            cx - r * 0.38f, cy - r * 0.44f, r * 0.95f,
            intArrayOf(Color.argb(95, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
        canvas.drawCircle(cx, cy, r, glossPaint)
        canvas.restore()
        glossPaint.shader = null

        drawCenterText(canvas, cx, cy, r)

        // ---- IDLE me pill card, warna status / hint line gole ke neeche
        if (state == State.IDLE) {
            drawPill(canvas, w, cy + r + dp(30f))
        } else {
            var size = dp(14.5f)
            if (hintText.length > 44) size = dp(12.5f)
            hintPaint.textSize = size
            canvas.drawText(hintText, w / 2f, cy + r + dp(56f), hintPaint)
        }
    }

    /** Reference design wala pas-e-manzar: upar halki lehrain, neeche
     *  hari + neeli bari lehrain. */
    private fun drawBackdrop(canvas: Canvas, w: Int, h: Int) {
        wavePaint.shader = null
        wavePaint.color = Color.argb(46, 66, 178, 232)
        var path = Path()
        path.moveTo(0f, 0f)
        path.lineTo(w * 0.46f, 0f)
        path.cubicTo(w * 0.30f, h * 0.035f, w * 0.16f, h * 0.012f, 0f, h * 0.052f)
        path.close()
        canvas.drawPath(path, wavePaint)
        wavePaint.color = Color.argb(40, 88, 181, 49)
        path = Path()
        path.moveTo(w.toFloat(), 0f)
        path.lineTo(w * 0.72f, 0f)
        path.cubicTo(w * 0.82f, h * 0.022f, w * 0.92f, h * 0.010f, w.toFloat(), h * 0.040f)
        path.close()
        canvas.drawPath(path, wavePaint)
        // neeche: hari lehar peeche
        wavePaint.color = greenWave
        path = Path()
        path.moveTo(0f, h * 0.885f)
        path.cubicTo(w * 0.30f, h * 0.815f, w * 0.62f, h * 0.905f, w.toFloat(), h * 0.815f)
        path.lineTo(w.toFloat(), h.toFloat())
        path.lineTo(0f, h.toFloat())
        path.close()
        canvas.drawPath(path, wavePaint)
        // neeche: neeli lehar aage (gradient)
        wavePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(blueDeep, blueMid, blueLight),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        path = Path()
        path.moveTo(0f, h * 0.925f)
        path.cubicTo(w * 0.34f, h * 0.855f, w * 0.60f, h * 0.95f, w.toFloat(), h * 0.875f)
        path.lineTo(w.toFloat(), h.toFloat())
        path.lineTo(0f, h.toFloat())
        path.close()
        canvas.drawPath(path, wavePaint)
        wavePaint.shader = null
    }

    /** Gole ke andar download ka nishan (teer + tray), safed. */
    private fun drawDownloadIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        iconPaint.color = Color.WHITE
        val top = cy - r * 0.60f
        canvas.drawRect(cx - r * 0.085f, top, cx + r * 0.085f, top + r * 0.40f, iconPaint)
        val head = Path()
        head.moveTo(cx - r * 0.30f, top + r * 0.38f)
        head.lineTo(cx + r * 0.30f, top + r * 0.38f)
        head.lineTo(cx, top + r * 0.68f)
        head.close()
        canvas.drawPath(head, iconPaint)
        iconStroke.strokeWidth = r * 0.075f
        val tray = Path()
        val ty = top + r * 0.52f
        val tb = top + r * 0.86f
        tray.moveTo(cx - r * 0.36f, ty)
        tray.lineTo(cx - r * 0.36f, tb - r * 0.10f)
        tray.quadTo(cx - r * 0.36f, tb, cx - r * 0.26f, tb)
        tray.lineTo(cx + r * 0.26f, tb)
        tray.quadTo(cx + r * 0.36f, tb, cx + r * 0.36f, tb - r * 0.10f)
        tray.lineTo(cx + r * 0.36f, ty)
        canvas.drawPath(tray, iconStroke)
    }

    /** Neeche wali safed pill: link icon + "Copy a video link..." (sirf IDLE me). */
    private fun drawPill(canvas: Canvas, w: Int, topY: Float) {
        val left = w * 0.09f
        val right = w * 0.91f
        val ph = dp(60f)
        val rect = RectF(left, topY, right, topY + ph)
        canvas.drawRoundRect(rect, ph / 2f, ph / 2f, pillPaint)
        val cyP = topY + ph / 2f
        val ix = left + dp(31f)
        iconPaint.color = blueMid
        canvas.drawCircle(ix, cyP, dp(17.5f), iconPaint)
        iconPaint.color = Color.WHITE
        iconStroke.strokeWidth = dp(2.8f)
        canvas.save()
        canvas.translate(ix, cyP)
        canvas.rotate(-45f)
        canvas.drawRoundRect(
            RectF(dp(-9.5f), dp(-5.5f), dp(2.5f), dp(5.5f)),
            dp(5.5f), dp(5.5f), iconStroke
        )
        canvas.drawRoundRect(
            RectF(dp(-2.5f), dp(-5.5f), dp(9.5f), dp(5.5f)),
            dp(5.5f), dp(5.5f), iconStroke
        )
        canvas.restore()
        val tx = left + dp(60f)
        pillTextPaint.textSize = dp(13f)
        canvas.drawText("Copy a video link, then tap", tx, cyP - dp(4f), pillTextPaint)
        pillBoldPaint.textSize = dp(15f)
        canvas.drawText("Download", tx, cyP + dp(15f), pillBoldPaint)
    }

    private fun drawBanner(canvas: Canvas, w: Int) {
        val mx = dp(14f)
        val top = dp(24f)
        val bh = dp(118f)
        val rect = RectF(mx, top, w - mx, top + bh)
        bannerPaint.shader = LinearGradient(
            rect.left, 0f, rect.right, 0f,
            intArrayOf(blueMid, blueDeep), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, dp(16f), dp(16f), bannerPaint)
        bannerPaint.shader = null
        // dayan-niche kone par hari swoosh (reference design jesi)
        canvas.save()
        canvas.clipPath(
            Path().apply { addRoundRect(rect, dp(16f), dp(16f), Path.Direction.CW) }
        )
        bannerPaint.color = Color.argb(150, 88, 199, 63)
        val swoosh = Path()
        swoosh.moveTo(rect.right - dp(96f), rect.bottom)
        swoosh.cubicTo(
            rect.right - dp(38f), rect.bottom - dp(50f),
            rect.right - dp(10f), rect.bottom - dp(34f),
            rect.right, rect.bottom - dp(64f)
        )
        swoosh.lineTo(rect.right, rect.bottom)
        swoosh.close()
        canvas.drawPath(swoosh, bannerPaint)
        canvas.restore()

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

        // naam: EXPERT SOLUTIONS (safed) / DOWNLOADER (hara) / tagline
        val textX = tileRect.right + dp(13f)
        val avail = rect.right - dp(10f) - textX
        var s1 = dp(20f)
        bannerTextPaint.letterSpacing = 0f
        bannerTextPaint.color = Color.WHITE
        while (s1 > dp(10f)) {
            bannerTextPaint.textSize = s1
            if (bannerTextPaint.measureText("EXPERT SOLUTIONS") <= avail) break
            s1 -= dp(1f)
        }
        val s2 = s1 * 0.88f
        val tagSize = dp(9.5f)
        val gap1 = dp(4f)
        val gap2 = dp(7f)
        val totalH = s1 + gap1 + s2 + gap2 + tagSize
        var baseline = rect.top + (bh - totalH) / 2f + s1 * 0.82f
        bannerTextPaint.textSize = s1
        canvas.drawText("EXPERT SOLUTIONS", textX, baseline, bannerTextPaint)
        baseline += s2 + gap1
        bannerTextPaint.textSize = s2
        bannerTextPaint.color = greenBright
        bannerTextPaint.letterSpacing = 0.22f
        canvas.drawText("DOWNLOADER", textX, baseline, bannerTextPaint)
        baseline += tagSize + gap2
        bannerTextPaint.textSize = tagSize
        bannerTextPaint.color = Color.argb(235, 255, 255, 255)
        bannerTextPaint.letterSpacing = 0.30f
        canvas.drawText("FAST  \u2022  SIMPLE  \u2022  RELIABLE", textX, baseline, bannerTextPaint)
        bannerTextPaint.letterSpacing = 0f
        bannerTextPaint.color = Color.WHITE
    }

    private fun drawCenterText(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fun text(txt: String, size: Float, dy: Float, paint: Paint) {
            paint.textSize = size
            val fm = paint.fontMetrics
            canvas.drawText(txt, cx, cy + dy - (fm.ascent + fm.descent) / 2f, paint)
        }
        when (state) {
            State.IDLE -> {
                drawDownloadIcon(canvas, cx, cy, r)
                text("DOWNLOAD", r * 0.205f, r * 0.52f, centerPaint)
                text("VIDEO", r * 0.205f, r * 0.80f, centerPaint)
            }
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
