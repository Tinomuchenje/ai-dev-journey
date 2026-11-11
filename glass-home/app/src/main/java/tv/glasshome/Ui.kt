package tv.glasshome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Everything is laid out on a virtual 1920x1080 canvas and scaled to the real panel.
 * Generic TV firmwares report all sorts of densities, so dp/sp cannot be trusted to
 * give a consistent "ten-foot" size; screen-relative units can.
 */
object Dim {
    var unit = 1f
        private set
    var w = 1920
        private set
    var h = 1080
        private set

    fun init(ctx: Context) {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val a = max(dm.widthPixels, dm.heightPixels)
        val b = min(dm.widthPixels, dm.heightPixels)
        if (a > 0 && b > 0) {
            w = a
            h = b
        }
        unit = min(w / 1920f, h / 1080f)
    }
}

fun u(v: Int): Int = if (v == 0) 0 else max(1, (v * Dim.unit).roundToInt())
fun uf(v: Float): Float = v * Dim.unit

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

fun withAlpha(color: Int, alpha: Float): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

object Fonts {
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

/** Soft white halo drawn behind a focused element. Rendered once per size and cached. */
object Glow {
    private val cache = HashMap<String, Bitmap>()

    fun pad(): Int = ceil(uf(20f) * 2.2f).toInt()

    fun get(w: Int, h: Int, radius: Float): Bitmap {
        val key = "$w:$h:${radius.roundToInt()}:${Dim.unit}"
        cache[key]?.let { return it }
        val blur = uf(20f)
        val pad = pad()
        val bmp = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        bmp.density = Bitmap.DENSITY_NONE // drawn 1:1 in pixels, never density-scaled
        val c = Canvas(bmp)
        val rect = RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(rect, radius, radius, p)
        // Punch out the middle so the halo never washes out the glass itself.
        p.maskFilter = null
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        rect.inset(1f, 1f)
        c.drawRoundRect(rect, radius, radius, p)
        cache[key] = bmp
        return bmp
    }
}

/**
 * A pane of "glass": translucent body, a soft highlight falling from the top edge and a
 * bright rim that fades toward the bottom-right. No live blur - the wallpaper behind it
 * is already soft, which gives the frosted look for free on a weak GPU.
 */
class GlassDrawable(
    private val radius: Float,
    private val bodyTop: Int,
    private val bodyBottom: Int,
    private val rimIdle: Float = 0.26f,
    private val rimFocus: Float = 0.95f,
    private val focusBody: Int = 0x38FFFFFF,
) : Drawable() {

    var focus = 0f
        set(v) {
            field = v
            invalidateSelf()
        }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun onBoundsChange(b: Rect) {
        val t = b.top.toFloat()
        val bt = b.bottom.toFloat()
        body.shader = LinearGradient(0f, t, 0f, bt, bodyTop, bodyBottom, Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(
            0f, t, 0f, t + b.height() * 0.6f,
            0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        rim.shader = LinearGradient(
            b.left.toFloat(), t, b.right.toFloat(), bt,
            intArrayOf(Color.WHITE, 0x66FFFFFF, 0xB3FFFFFF.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(c: Canvas) {
        rect.set(bounds)
        c.drawRoundRect(rect, radius, radius, body)
        if (focus > 0f) {
            lit.color = focusBody
            lit.alpha = (Color.alpha(focusBody) * focus).roundToInt()
            c.drawRoundRect(rect, radius, radius, lit)
        }
        c.drawRoundRect(rect, radius, radius, sheen)
        val sw = lerp(uf(1.5f), uf(3f), focus)
        rim.strokeWidth = sw
        rim.alpha = (lerp(rimIdle, rimFocus, focus) * 255).roundToInt()
        rect.inset(sw / 2f, sw / 2f)
        c.drawRoundRect(rect, radius - sw / 2f, radius - sw / 2f, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /**
         * Darker pane for menus where text must stay readable. Where the OS can blur what is
         * behind it (Android 12+) it stays see-through; otherwise it goes nearly solid.
         */
        fun panel(radius: Float) = if (Build.VERSION.SDK_INT >= 31) {
            GlassDrawable(radius, 0xD01B2136.toInt(), 0xE0101424.toInt(), 0.4f, 0.4f)
        } else {
            GlassDrawable(radius, 0xF51B2136.toInt(), 0xFA101424.toInt(), 0.4f, 0.4f)
        }

        /** Menu row / text field. */
        fun row(radius: Float) = GlassDrawable(radius, 0x14FFFFFF, 0x0DFFFFFF, 0.14f, 0.9f, 0x40FFFFFF)
    }
}

/** Thin-line icons drawn in code so they stay crisp at any panel resolution. */
object Glyphs {
    const val NONE = 0
    const val PLUS = 1
    const val GLOBE = 2
    const val GEAR = 3
    const val WIFI = 4
    const val WIRED = 5
    const val OFFLINE = 6
    const val EYE_OFF = 7

    private val path = Path()
    private val oval = RectF()

    fun draw(c: Canvas, kind: Int, cx: Float, cy: Float, size: Float, p: Paint) {
        val r = size / 2f
        p.style = Paint.Style.STROKE
        p.strokeWidth = size * 0.085f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        when (kind) {
            PLUS -> {
                c.drawLine(cx - r * 0.8f, cy, cx + r * 0.8f, cy, p)
                c.drawLine(cx, cy - r * 0.8f, cx, cy + r * 0.8f, p)
            }
            GLOBE -> {
                val rr = r * 0.92f
                c.drawCircle(cx, cy, rr, p)
                oval.set(cx - rr * 0.45f, cy - rr, cx + rr * 0.45f, cy + rr)
                c.drawOval(oval, p)
                c.drawLine(cx - rr, cy, cx + rr, cy, p)
                val k = rr * 0.86f
                c.drawLine(cx - k, cy - rr * 0.5f, cx + k, cy - rr * 0.5f, p)
                c.drawLine(cx - k, cy + rr * 0.5f, cx + k, cy + rr * 0.5f, p)
            }
            GEAR -> {
                path.rewind()
                val outer = r * 0.96f
                val inner = r * 0.72f
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val pts = arrayOf(
                        doubleArrayOf(-0.30, inner.toDouble()),
                        doubleArrayOf(-0.16, outer.toDouble()),
                        doubleArrayOf(0.16, outer.toDouble()),
                        doubleArrayOf(0.30, inner.toDouble()),
                    )
                    for ((j, pt) in pts.withIndex()) {
                        val x = cx + (pt[1] * cos(a + pt[0])).toFloat()
                        val y = cy + (pt[1] * sin(a + pt[0])).toFloat()
                        if (i == 0 && j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                }
                path.close()
                c.drawPath(path, p)
                c.drawCircle(cx, cy, r * 0.3f, p)
            }
            WIFI, OFFLINE -> {
                val by = cy + r * 0.62f
                for (k in floatArrayOf(1.42f, 0.95f, 0.5f)) {
                    val rr = r * k
                    oval.set(cx - rr, by - rr, cx + rr, by + rr)
                    c.drawArc(oval, 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(cx, by - r * 0.04f, size * 0.07f, p)
                if (kind == OFFLINE) {
                    p.style = Paint.Style.STROKE
                    c.drawLine(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f, p)
                }
            }
            WIRED -> {
                path.rewind()
                path.moveTo(cx - r * 0.45f, cy - r * 0.6f)
                path.lineTo(cx - r, cy)
                path.lineTo(cx - r * 0.45f, cy + r * 0.6f)
                path.moveTo(cx + r * 0.45f, cy - r * 0.6f)
                path.lineTo(cx + r, cy)
                path.lineTo(cx + r * 0.45f, cy + r * 0.6f)
                c.drawPath(path, p)
                p.style = Paint.Style.FILL
                for (dx in floatArrayOf(-0.36f, 0f, 0.36f)) c.drawCircle(cx + r * dx, cy, size * 0.06f, p)
            }
            EYE_OFF -> {
                path.rewind()
                path.moveTo(cx - r, cy)
                path.quadTo(cx, cy - r * 1.15f, cx + r, cy)
                path.quadTo(cx, cy + r * 1.15f, cx - r, cy)
                path.close()
                c.drawPath(path, p)
                c.drawCircle(cx, cy, r * 0.28f, p)
                c.drawLine(cx - r * 0.8f, cy + r * 0.8f, cx + r * 0.8f, cy - r * 0.8f, p)
            }
        }
        p.style = Paint.Style.STROKE
    }
}

object Tint {
    const val NEUTRAL = 0

    /** A saturated-leaning average of an icon, used to give each tile its own glass colour. */
    fun from(bmp: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val px = IntArray(24 * 24)
        s.getPixels(px, 0, 24, 0, 0, 24, 24)
        if (s !== bmp) s.recycle()
        val hsv = FloatArray(3)
        var r = 0f
        var g = 0f
        var b = 0f
        var sum = 0f
        for (p in px) {
            if (Color.alpha(p) < 128) continue
            Color.colorToHSV(p, hsv)
            val w = 0.04f + hsv[1] * hsv[1] * hsv[2]
            r += Color.red(p) * w
            g += Color.green(p) * w
            b += Color.blue(p) * w
            sum += w
        }
        if (sum <= 0f) return NEUTRAL
        Color.colorToHSV(Color.rgb((r / sum).toInt(), (g / sum).toInt(), (b / sum).toInt()), hsv)
        if (hsv[1] < 0.12f) return NEUTRAL
        hsv[1] = (hsv[1] * 1.1f).coerceIn(0.35f, 0.8f)
        hsv[2] = hsv[2].coerceIn(0.42f, 0.74f)
        return Color.HSVToColor(hsv)
    }

    /** Stable colour for a website, derived from its host name. */
    fun fromText(text: String): Int {
        var hsh = 7
        for (ch in text) hsh = hsh * 31 + ch.code
        val hue = ((hsh % 360) + 360) % 360
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.62f, 0.66f))
    }

    fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )
}
