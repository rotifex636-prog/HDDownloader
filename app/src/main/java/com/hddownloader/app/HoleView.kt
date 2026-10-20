package com.hddownloader.app

import android.content.Context
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Poori home screen ek hi canvas par: starry black background, upar gradient
 * naam, beech me pink gola (hole) gird ghumte hue arcs + progress ring,
 * neeche hint text aur VIDEOS. Green Hole style — Expert Solutions pink theme.
 */
class HoleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { IDLE, PROCESSING, DOWNLOADING, DONE, ERROR }

    interface Listener {
        fun onHoleTap()
        fun onInfoTap()
        fun onHeartTap()
        fun onVideosTap()
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

    private val pink = Color.rgb(245, 30, 154)       // sphere mid / accents
    private val pinkBright = Color.rgb(255, 46, 166) // arcs
    private val pinkLight = Color.rgb(255, 155, 214) // gradient start

    private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val pinkish: Boolean)

    private val stars: List<Star> = run {
        val rnd = Random(20261021)
        List(150) {
            Star(
                rnd.nextFloat(), rnd.nextFloat(),
                0.6f + rnd.nextFloat() * 1.5f,
                rnd.nextFloat() * 6.28f,
                rnd.nextFloat() < 0.3f
            )
        }
    }

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(255, 92, 184)
        alpha = 190
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = pinkBright
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = pinkBright
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(22f, 0f, 0f, pink)
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        setShadowLayer(16f, 0f, 0f, Color.rgb(190, 0, 110))
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.rgb(184, 184, 196)
    }
    private val videosPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = pinkBright
        letterSpacing = 0.35f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pinkBright }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(216, 216, 224)
    }
    private val infoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(216, 216, 224) }
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pinkBright }

    private var animating = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!animating) return
            invalidate()
            postDelayed(this, 50)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animating = true
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        animating = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun sphereCx(w: Int) = w / 2f
    private fun sphereCy(h: Int) = h * 0.465f
    private fun sphereR(w: Int, h: Int) = min(w, h) * 0.265f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val t = SystemClock.uptimeMillis() / 1000f

        // ---- stars
        for (s in stars) {
            val tw = 0.55f + 0.45f * sin(t * 1.7f + s.phase)
            starPaint.color = if (s.pinkish) pinkLight else Color.WHITE
            starPaint.alpha = (110 + 130 * tw).toInt().coerceIn(0, 255)
            canvas.drawCircle(s.x * w, s.y * h, dp(s.r), starPaint)
        }

        val cx = sphereCx(w)
        val cy = sphereCy(h)
        val r = sphereR(w, h)

        drawTitle(canvas, w, h)
        drawInfoAndHeart(canvas, w, h)

        // ---- soft glow behind sphere
        glowPaint.shader = RadialGradient(
            cx, cy, r * 1.7f,
            intArrayOf(Color.argb(105, 245, 30, 154), Color.argb(0, 245, 30, 154)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.7f, glowPaint)
        glowPaint.shader = null

        // ---- dashed rotating ring
        val rot = (t * 14f) % 360f
        dashPaint.strokeWidth = dp(3f)
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(9f), dp(7f)), 0f)
        canvas.save()
        canvas.rotate(rot, cx, cy)
        canvas.drawCircle(cx, cy, r + dp(13f), dashPaint)
        canvas.restore()

        // ---- solid decorative arcs (idle life) + progress arc
        val arcRect = RectF(cx - r - dp(24f), cy - r - dp(24f), cx + r + dp(24f), cy + r + dp(24f))
        arcPaint.strokeWidth = dp(6.5f)
        if (state == State.IDLE) {
            val base = (t * 22f) % 360f
            canvas.drawArc(arcRect, base, 68f, false, arcPaint)
            canvas.drawArc(arcRect, base + 175f, 46f, false, arcPaint)
        } else {
            // halki si ghumti hui arc peeche rehti hai
            val base = (t * 22f) % 360f
            arcPaint.alpha = 70
            canvas.drawArc(arcRect, base, 68f, false, arcPaint)
            arcPaint.alpha = 255
            progressPaint.strokeWidth = dp(8.5f)
            val sweep = percent * 3.6f
            if (sweep > 0f) canvas.drawArc(arcRect, -90f, sweep, false, progressPaint)
        }

        // ---- sphere
        spherePaint.shader = RadialGradient(
            cx - r * 0.28f, cy - r * 0.32f, r * 1.3f,
            intArrayOf(pinkLight, pink, Color.rgb(178, 0, 108)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, spherePaint)
        spherePaint.shader = null

        drawCenterText(canvas, cx, cy, r)
        drawBottom(canvas, w, h, cy, r)
    }

    private fun drawTitle(canvas: Canvas, w: Int, h: Int) {
        val size = w * 0.082f
        titlePaint.textSize = size
        titlePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(pinkBright, pinkLight, Color.WHITE),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        val x = w / 2f
        canvas.drawText("EXPERT SOLUTIONS", x, h * 0.145f, titlePaint)
        canvas.drawText("DOWNLOADER", x, h * 0.145f + size * 1.22f, titlePaint)
        titlePaint.shader = null
    }

    private fun drawInfoAndHeart(canvas: Canvas, w: Int, h: Int) {
        val y = h * 0.062f
        // info (i) — left
        val ix = dp(34f)
        infoPaint.strokeWidth = dp(1.8f)
        canvas.drawCircle(ix, y, dp(12.5f), infoPaint)
        canvas.drawCircle(ix, y - dp(5f), dp(1.7f), infoFillPaint)
        canvas.drawLine(ix, y - dp(1f), ix, y + dp(6.5f), infoPaint)
        // heart — right (parametric heart curve)
        val hx = w - dp(34f)
        val s = dp(1.05f)
        val path = Path()
        var first = true
        var a = 0.0
        while (a <= Math.PI * 2) {
            val px = 16 * Math.pow(sin(a), 3.0)
            val py = 13 * cos(a) - 5 * cos(2 * a) - 2 * cos(3 * a) - cos(4 * a)
            val x = hx + (px * s).toFloat()
            val yy = y - (py * s).toFloat()
            if (first) {
                path.moveTo(x, yy); first = false
            } else path.lineTo(x, yy)
            a += 0.12
        }
        path.close()
        canvas.drawPath(path, heartPaint)
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

    private fun drawBottom(canvas: Canvas, w: Int, h: Int, cy: Float, r: Float) {
        // hint / status line under the sphere
        var size = dp(14.5f)
        if (hintText.length > 42) size = dp(12.5f)
        hintPaint.textSize = size
        canvas.drawText(hintText, w / 2f, cy + r + dp(58f), hintPaint)

        // VIDEOS with dot cluster — bottom center
        val vy = h - dp(52f)
        val dotY = vy - dp(30f)
        val cx = w / 2f
        val offs = arrayOf(
            0f to -7f, -7f to -2f, 7f to -2f, 0f to 1.5f, -4.5f to 6f, 4.5f to 6f, 0f to 10f
        )
        for ((dx, dy) in offs) {
            canvas.drawCircle(cx + dp(dx), dotY + dp(dy) * 0.8f, dp(2.4f), dotPaint)
        }
        videosPaint.textSize = dp(14f)
        canvas.drawText("VIDEOS", cx, vy + dp(14f), videosPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            val w = width
            val h = height
            val topY = h * 0.062f
            fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
                Math.hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()

            when {
                dist(x, y, dp(34f), topY) < dp(36f) -> listener?.onInfoTap()
                dist(x, y, w - dp(34f), topY) < dp(36f) -> listener?.onHeartTap()
                y > h - dp(120f) && Math.abs(x - w / 2f) < dp(90f) -> listener?.onVideosTap()
                dist(x, y, sphereCx(w), sphereCy(h)) < sphereR(w, h) + dp(34f) -> {
                    performClick()
                    listener?.onHoleTap()
                }
                else -> return super.onTouchEvent(event)
            }
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
