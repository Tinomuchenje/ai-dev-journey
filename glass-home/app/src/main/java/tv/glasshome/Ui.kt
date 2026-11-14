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
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
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

/**
 * The interface is set in Inter (bundled, Latin, 120 KB), cut at four weights from one
 * variable file. Before Android 8, or if anything goes wrong, the system face is used.
 */
object Fonts {
    var thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        private set
    var light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        private set
    var regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private set
    var medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private set
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        if (Build.VERSION.SDK_INT < 26) return
        try {
            fun weight(w: Int) = Typeface.Builder(ctx.assets, "inter.ttf").setFontVariationSettings("'wght' $w").build()
            val cut = listOf(weight(200), weight(300), weight(400), weight(560))
            thin = cut[0]
            light = cut[1]
            regular = cut[2]
            medium = cut[3]
        } catch (e: Exception) {
            // keep the system faces
        }
    }
}

/** Ink used for text and glyphs on a focused (white) control. */
const val INK = 0xFF12141A.toInt()

/**
 * Soft blurred rounded rectangles used for drop shadows and coloured glows. Each size is
 * rendered once in white and tinted when drawn.
 */
object Shadow {
    private val cache = HashMap<String, Bitmap>()
    private val filters = HashMap<Int, PorterDuffColorFilter>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun pad(blur: Float): Int = ceil(blur * 2.4f).toInt()

    private fun get(w: Int, h: Int, radius: Float, blur: Float, punched: Boolean): Bitmap {
        val key = "$w:$h:${radius.roundToInt()}:${blur.roundToInt()}:$punched"
        cache[key]?.let { return it }
        val pad = pad(blur)
        val bmp = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        bmp.density = Bitmap.DENSITY_NONE // drawn 1:1 in pixels, never density-scaled
        val c = Canvas(bmp)
        val rect = RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(rect, radius, radius, p)
        if (punched) {
            // For see-through glass: keep the shadow around the shape, not behind it.
            p.maskFilter = null
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            rect.inset(1f, 1f)
            c.drawRoundRect(rect, radius, radius, p)
        }
        cache[key] = bmp
        return bmp
    }

    fun draw(
        c: Canvas, left: Float, top: Float, w: Int, h: Int, radius: Float,
        blur: Float, color: Int, alpha: Float, dy: Float = 0f, punched: Boolean = false,
    ) {
        if (alpha <= 0.004f || w <= 0 || h <= 0) return
        val pad = pad(blur)
        val rgb = color or 0xFF000000.toInt()
        paint.colorFilter = filters.getOrPut(rgb) { PorterDuffColorFilter(rgb, PorterDuff.Mode.SRC_IN) }
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        c.drawBitmap(get(w, h, radius, blur, punched), left - pad, top - pad + dy, paint)
    }
}

/**
 * A pane of "liquid glass": a faint translucent body, a soft sheen falling from the top
 * edge, and a rim that catches light at the top-left and bottom-right corners. There is
 * no live blur - the backdrop behind it is already soft, which gives the frosted look
 * for free on a weak GPU. Focused glass turns bright white, the way tvOS controls do.
 */
class GlassDrawable(
    private val radius: Float,
    private val bodyTop: Int,
    private val bodyBottom: Int,
    private val rimAlpha: Float,
    private val focusFill: Int = 0,
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
            0f, t, 0f, t + b.height() * 0.5f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        rim.shader = LinearGradient(
            b.left.toFloat(), t, b.right.toFloat(), bt,
            intArrayOf(Color.WHITE, 0x24FFFFFF, 0x1AFFFFFF, 0x8CFFFFFF.toInt()),
            floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(c: Canvas) {
        rect.set(bounds)
        c.drawRoundRect(rect, radius, radius, body)
        if (focus > 0f && focusFill != 0) {
            lit.color = focusFill
            lit.alpha = (Color.alpha(focusFill) * focus).roundToInt()
            c.drawRoundRect(rect, radius, radius, lit)
        }
        c.drawRoundRect(rect, radius, radius, sheen)
        val sw = uf(1.6f)
        rim.strokeWidth = sw
        rim.alpha = (rimAlpha * 255).roundToInt()
        rect.inset(sw / 2f, sw / 2f)
        c.drawRoundRect(rect, radius - sw / 2f, radius - sw / 2f, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** The dock that holds the favourites. */
        fun tray(radius: Float) = GlassDrawable(radius, 0x24FFFFFF, 0x10FFFFFF, 0.6f)

        /** Round buttons in the top corner. */
        fun chip(radius: Float) = GlassDrawable(radius, 0x2BFFFFFF, 0x14FFFFFF, 0.6f, 0xF5FFFFFF.toInt())

        /** Neutral tile, e.g. "Add website". */
        fun tile(radius: Float) = GlassDrawable(radius, 0x26FFFFFF, 0x10FFFFFF, 0.55f, 0x33FFFFFF)

        /**
         * Darker pane for menus where text must stay readable. Where the OS can blur what is
         * behind it (Android 12+) it stays see-through; otherwise it goes nearly solid.
         */
        fun panel(radius: Float) = if (Build.VERSION.SDK_INT >= 31) {
            GlassDrawable(radius, 0xB82A2F3F.toInt(), 0xCC161821.toInt(), 0.55f)
        } else {
            GlassDrawable(radius, 0xF5262A38.toInt(), 0xFA14161F.toInt(), 0.5f)
        }

        /** Menu row / text field: turns white when focused. */
        fun row(radius: Float) = GlassDrawable(radius, 0x14FFFFFF, 0x0CFFFFFF, 0.22f, 0xF5FFFFFF.toInt())
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
    const val INPUT = 8

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
            INPUT -> {
                // A screen with an arrow going into it.
                oval.set(cx - r, cy - r * 0.72f, cx + r, cy + r * 0.72f)
                c.drawRoundRect(oval, r * 0.22f, r * 0.22f, p)
                c.drawLine(cx - r * 0.5f, cy, cx + r * 0.38f, cy, p)
                path.rewind()
                path.moveTo(cx + r * 0.08f, cy - r * 0.3f)
                path.lineTo(cx + r * 0.4f, cy)
                path.lineTo(cx + r * 0.08f, cy + r * 0.3f)
                c.drawPath(path, p)
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
    /** Used when nothing better is known. */
    const val DEFAULT = 0xFF3E5C9A.toInt()

    /**
     * The colour an icon "feels like": an average that leans toward its saturated pixels.
     * Black, white and grey icons give a matching neutral instead of a made-up hue.
     */
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
        var luma = 0f
        var count = 0
        for (p in px) {
            if (Color.alpha(p) < 128) continue
            Color.colorToHSV(p, hsv)
            val w = 0.02f + hsv[1] * hsv[1] * hsv[2]
            r += Color.red(p) * w
            g += Color.green(p) * w
            b += Color.blue(p) * w
            sum += w
            luma += (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
            count++
        }
        if (count == 0) return DEFAULT
        Color.colorToHSV(Color.rgb((r / sum).toInt(), (g / sum).toInt(), (b / sum).toInt()), hsv)
        if (hsv[1] < 0.18f) {
            val v = (luma / count).coerceIn(0.16f, 0.8f)
            return Color.HSVToColor(floatArrayOf(225f, 0.08f, v))
        }
        hsv[1] = (hsv[1] * 1.1f).coerceIn(0.4f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.8f)
        return Color.HSVToColor(hsv)
    }

    /** Stable colour for a website, derived from its host name. */
    fun fromText(text: String): Int {
        var hsh = 7
        for (ch in text) hsh = hsh * 31 + ch.code
        val hue = ((hsh % 360) + 360) % 360
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.6f, 0.62f))
    }

    fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )

    /** A lively version of [color] for glows, whatever its original brightness. */
    fun glow(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] < 0.15f) return 0xFFDCE4FF.toInt()
        hsv[1] = hsv[1].coerceIn(0.5f, 0.8f)
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }
}

/** Builds the 16:9 artwork for things that only come with a square icon. */
object Art {
    private val smooth = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * A phone-style icon becomes a TV-style tile: the icon itself, hugely enlarged and
     * blurred, forms a rich backdrop in the app's own colours, and the sharp icon floats
     * on top of it with a soft shadow.
     */
    fun tile(icon: Bitmap, w: Int, h: Int, iconScale: Float = 0.62f): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.density = Bitmap.DENSITY_NONE
        val c = Canvas(out)

        // Backdrop: the icon blown up past the tile, shrunk to a few pixels, then smoothed.
        val sw = 32
        val sh = 18
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val sc = Canvas(small)
        sc.drawColor(from(icon))
        val iw = sw * 2.3f // far past the edges, so only the heart of the icon colours the tile
        sc.drawBitmap(icon, null, RectF((sw - iw) / 2, (sh - iw) / 2, (sw + iw) / 2, (sh + iw) / 2), smooth)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(3) { boxBlur(px, sw, sh) }
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        val rich = Paint(Paint.FILTER_BITMAP_FLAG)
        val cm = android.graphics.ColorMatrix()
        cm.setSaturation(1.3f)
        val dim = android.graphics.ColorMatrix()
        dim.setScale(0.8f, 0.8f, 0.8f, 1f)
        cm.postConcat(dim)
        rich.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        c.drawBitmap(small, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), rich)
        small.recycle()
        finish(c, w, h)

        // The icon, floating.
        val s = (h * iconScale).roundToInt()
        val scaled = Bitmap.createScaledBitmap(icon, s, s, true)
        val left = (w - s) / 2f
        val top = (h - s) / 2f
        val blur = Paint()
        blur.maskFilter = BlurMaskFilter(h * 0.07f, BlurMaskFilter.Blur.NORMAL)
        val off = IntArray(2)
        val alpha = scaled.extractAlpha(blur, off)
        val sp = Paint()
        sp.color = 0x73000000
        c.drawBitmap(alpha, left + off[0], top + off[1] + h * 0.045f, sp)
        alpha.recycle()
        c.drawBitmap(scaled, left, top, smooth)
        if (scaled !== icon) scaled.recycle()
        return out
    }

    /** Artwork for a website with no usable icon: its initial, set large and light. */
    fun monogram(letter: String, color: Int, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.density = Bitmap.DENSITY_NONE
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(), Tint.shade(color, 1.15f), Tint.shade(color, 0.6f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        finish(c, w, h)
        p.shader = null
        p.color = Color.WHITE
        p.typeface = Fonts.light
        p.textAlign = Paint.Align.CENTER
        p.textSize = h * 0.5f
        p.setShadowLayer(h * 0.05f, 0f, h * 0.02f, 0x55000000)
        c.drawText(letter, w / 2f, h / 2f - (p.ascent() + p.descent()) / 2f, p)
        return out
    }

    /** Shared lighting: darker toward the bottom-right, a soft highlight from the top-left. */
    private fun finish(c: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0x00000000, 0x61000000, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.18f, 0f, w * 0.85f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    private fun from(icon: Bitmap) = Tint.shade(Tint.from(icon), 0.9f)

    private fun boxBlur(px: IntArray, w: Int, h: Int) {
        val src = px.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                val p = src[yy * w + xx]
                a += p ushr 24
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
            px[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
}
