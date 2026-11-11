package tv.glasshome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.max
import kotlin.math.roundToInt

/** Shared focus behaviour: a 0..1 "lift" value animated on focus or pointer hover. */
abstract class FocusView(ctx: Context) : View(ctx) {
    protected var lift = 0f
    private var anim: ValueAnimator? = null

    init {
        isFocusable = true
        isClickable = true
        // Plenty of generic TV firmwares boot in "touch mode" like a tablet, where ordinary
        // views refuse focus. A remote-first screen must always be able to show a highlight.
        isFocusableInTouchMode = true
        // We draw our own highlight; switch off the grey box Android 8+ adds by default.
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }

    private fun refresh() {
        val target = if (isFocused || isHovered) 1f else 0f
        translationZ = if (target > 0f) uf(8f) else 0f
        anim?.cancel()
        if (!isAttachedToWindow) {
            lift = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(lift, target).apply {
            duration = 170
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                lift = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        refresh()
    }

    // With an air mouse, pointing at something selects it, so one click is enough.
    override fun onHoverChanged(hovered: Boolean) {
        super.onHoverChanged(hovered)
        if (hovered && !isFocused) requestFocus() else refresh()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * One home-screen tile, in the manner of a tvOS app icon: a 16:9 piece of artwork with
 * rounded corners that sits quietly until focused, then lifts toward you - it grows,
 * throws a glow in its own colour, catches a streak of light, and shows its name.
 * Drawn entirely in onDraw: one cheap display list per tile, no nested layouts.
 */
class TileView(
    ctx: Context,
    private val cardW: Int,
    private val cardH: Int,
    private val labelDrop: Float = uf(42f),
) : FocusView(ctx) {
    var key = ""
    var label = ""
        set(v) {
            field = v
            contentDescription = v
            fitted = null
        }

    /** Full-bleed artwork. Without it the tile is a neutral pane of glass showing [glyph]. */
    var art: Bitmap? = null
        set(v) {
            field = v
            prepared = false
            invalidate()
        }
    var glyph = Glyphs.NONE
    var tint = Tint.DEFAULT

    private val pad = PAD
    private val radius = cardH * 0.16f
    private val rect = RectF()
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val glass = GlassDrawable.tile(radius)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Fonts.medium
        textAlign = Paint.Align.CENTER
        textSize = uf(26f)
        setShadowLayer(uf(8f), 0f, uf(1f), 0x80000000.toInt())
    }
    private var fitted: String? = null
    private var prepared = false

    private fun prepare() {
        prepared = true
        val left = pad.toFloat()
        val top = pad.toFloat()
        val bottom = top + cardH
        sheen.shader = LinearGradient(0f, top, 0f, top + cardH * 0.5f, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        streak.shader = LinearGradient(
            left, top, left + cardW * 0.9f, bottom,
            intArrayOf(0x00FFFFFF, 0x3DFFFFFF, 0x00FFFFFF), floatArrayOf(0.1f, 0.42f, 0.7f), Shader.TileMode.CLAMP
        )
        edge.shader = LinearGradient(0f, top, 0f, bottom, 0x66FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP)
        edge.strokeWidth = uf(1.5f)
        art?.let { b ->
            val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix()
            m.setScale(cardW / b.width.toFloat(), cardH / b.height.toFloat())
            m.postTranslate(left, top)
            shader.setLocalMatrix(m)
            artPaint.shader = shader
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(cardW + pad * 2, cardH + pad * 2 + LABEL_H)
    }

    override fun onDraw(c: Canvas) {
        if (!prepared) prepare()
        val cx = width / 2f
        val cy = pad + cardH / 2f
        val scale = (1f + SCALE * lift) * (if (isPressed) 0.97f else 1f)
        val l = pad.toFloat()
        val t = pad.toFloat()

        c.save()
        c.scale(scale, scale, cx, cy)
        val solid = art != null
        // Resting: a quiet contact shadow. Focused: a deep shadow plus light in the tile's colour.
        Shadow.draw(c, l, t, cardW, cardH, radius, uf(12f), Color.BLACK, lerp(0.42f, 0f, lift), uf(7f), !solid)
        Shadow.draw(c, l, t, cardW, cardH, radius, uf(34f), Color.BLACK, 0.6f * lift, uf(26f), !solid)
        Shadow.draw(c, l, t, cardW, cardH, radius, uf(38f), Tint.glow(tint), 0.7f * lift, uf(8f), !solid)

        rect.set(l, t, l + cardW, t + cardH)
        if (solid) {
            c.drawRoundRect(rect, radius, radius, artPaint)
            c.drawRoundRect(rect, radius, radius, sheen)
        } else {
            glass.setBounds(pad, pad, pad + cardW, pad + cardH)
            glass.focus = lift
            glass.draw(c)
            glyphPaint.alpha = (lerp(0.7f, 1f, lift) * 255).roundToInt()
            Glyphs.draw(c, glyph, cx, cy, cardH * 0.3f, glyphPaint)
        }
        if (lift > 0.01f) {
            streak.alpha = (255 * lift).roundToInt()
            c.drawRoundRect(rect, radius, radius, streak)
        }
        if (solid) {
            val half = edge.strokeWidth / 2f
            rect.inset(half, half)
            edge.alpha = (255 * (1f - lift)).roundToInt()
            c.drawRoundRect(rect, radius - half, radius - half, edge)
            rect.inset(-half, -half)
        }
        if (lift > 0.01f) {
            val sw = uf(3f)
            ring.strokeWidth = sw
            ring.alpha = (235 * lift).roundToInt()
            rect.inset(sw / 2f, sw / 2f)
            c.drawRoundRect(rect, radius - sw / 2f, radius - sw / 2f, ring)
        }
        c.restore()

        // Like tvOS, only the focused tile is captioned.
        if (lift > 0.01f) {
            val text = fitted ?: TextUtils.ellipsize(
                label, labelPaint, cardW * 1.25f, TextUtils.TruncateAt.END
            ).toString().also { fitted = it }
            labelPaint.alpha = (255 * lift).roundToInt()
            val grow = (scale - 1f) * cardH / 2f
            c.drawText(text, cx, pad + cardH + grow + labelDrop - uf(6f) * (1f - lift), labelPaint)
        }
    }

    companion object {
        const val SCALE = 0.1f
        val PAD get() = u(18)
        val LABEL_H get() = u(50)
    }
}

/** Round glass button for the top corner. Focused, it turns white with a dark glyph. */
class ChipView(ctx: Context, glyph: Int, description: String) : FocusView(ctx) {
    var glyph = glyph
        set(v) {
            field = v
            invalidate()
        }

    private val outer = u(12)
    private val size = u(76)
    private val glass = GlassDrawable.chip(size / 2f)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = description
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(size + outer * 2, size + outer * 2)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val scale = (1f + 0.12f * lift) * (if (isPressed) 0.96f else 1f)
        c.save()
        c.scale(scale, scale, cx, cy)
        val o = outer.toFloat()
        Shadow.draw(c, o, o, size, size, size / 2f, uf(14f), Color.BLACK, lerp(0.28f, 0.5f, lift), uf(6f), true)
        Shadow.draw(c, o, o, size, size, size / 2f, uf(22f), Color.WHITE, 0.4f * lift, 0f, true)
        glass.setBounds(outer, outer, outer + size, outer + size)
        glass.focus = lift
        glass.draw(c)
        glyphPaint.color = blend(Color.WHITE, INK, lift)
        Glyphs.draw(c, glyph, cx, cy, size * 0.4f, glyphPaint)
        c.restore()
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        lerp(Color.red(a).toFloat(), Color.red(b).toFloat(), t).roundToInt(),
        lerp(Color.green(a).toFloat(), Color.green(b).toFloat(), t).roundToInt(),
        lerp(Color.blue(a).toFloat(), Color.blue(b).toFloat(), t).roundToInt(),
    )
}

/**
 * The backdrop behind everything. It holds two images and cross-fades between them, so
 * the room can change colour as the highlight moves without redrawing gradients per frame.
 */
class BackdropView(ctx: Context) : View(ctx) {
    private var from: Bitmap? = null
    private var to: Bitmap? = null
    private var mix = 1f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = RectF()

    fun show(image: Bitmap, animate: Boolean) {
        if (image === to) return
        anim?.cancel()
        from = if (mix >= 1f) to else from ?: to
        to = image
        if (!animate || from == null || !isAttachedToWindow) {
            mix = 1f
            invalidate()
            return
        }
        mix = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                mix = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        val a = from
        if (a != null && mix < 1f) {
            paint.alpha = 255
            c.drawBitmap(a, null, dst, paint)
        }
        to?.let {
            paint.alpha = (255 * mix).roundToInt()
            c.drawBitmap(it, null, dst, paint)
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        mix = 1f
        super.onDetachedFromWindow()
    }
}

/**
 * A horizontal strip of focusable children. Left/right never leaves the strip, so the
 * highlight cannot fly off diagonally to some unrelated row when a strip ends.
 */
open class RowLayout(ctx: Context) : LinearLayout(ctx) {
    init {
        orientation = HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (focused != null && (direction == FOCUS_LEFT || direction == FOCUS_RIGHT)) {
            var child: View = focused
            while (child.parent !== this) {
                child = child.parent as? View ?: return super.focusSearch(focused, direction)
            }
            val step = if (direction == FOCUS_LEFT) -1 else 1
            var i = indexOfChild(child) + step
            while (i in 0 until childCount) {
                val next = getChildAt(i)
                if (next.visibility == VISIBLE && next.isFocusable) return next
                i += step
            }
            return focused
        }
        return super.focusSearch(focused, direction)
    }
}

/** Vertical scroller that glides the focused row to the middle of the screen. */
class CenterScrollView(ctx: Context) : ScrollView(ctx) {
    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipChildren = false
        clipToPadding = false
        isFocusable = false
    }

    // Let the framework's normal focus search drive the D-pad; we only follow the focus.
    override fun executeKeyEvent(event: KeyEvent): Boolean = false

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0) return 0
        val maxScroll = max(0, getChildAt(0).height + paddingTop + paddingBottom - height)
        val delta = (rect.centerY() - height / 2).coerceIn(0, maxScroll) - scrollY
        if (delta == 0) return 0
        if (!isLaidOut) return delta
        smoothScrollBy(0, delta)
        return 0
    }
}

/** Horizontal counterpart: keeps the focused tile centred in a strip. */
class CenterHScrollView(ctx: Context) : HorizontalScrollView(ctx) {
    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipChildren = false
        clipToPadding = false
        isFocusable = false
    }

    override fun executeKeyEvent(event: KeyEvent): Boolean = false

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (childCount == 0) return 0
        val maxScroll = max(0, getChildAt(0).width + paddingLeft + paddingRight - width)
        val delta = (rect.centerX() - width / 2).coerceIn(0, maxScroll) - scrollX
        if (delta == 0) return 0
        if (!isLaidOut) return delta
        smoothScrollBy(delta, 0)
        return 0
    }
}

/** The favourites dock: a strip of tiles resting in a tray of glass. */
class DockLayout(ctx: Context) : RowLayout(ctx) {
    private val radius = uf(44f)
    private val glass = GlassDrawable.tray(radius)

    init {
        setWillNotDraw(false)
        setPadding(INSET, INSET, INSET, 0)
    }

    override fun onDraw(c: Canvas) {
        // The tray ends under the tiles; the caption of the focused tile hangs below it.
        val bottom = height - TileView.LABEL_H + INSET
        Shadow.draw(c, 0f, 0f, width, bottom, radius, uf(26f), Color.BLACK, 0.4f, uf(4f), true)
        glass.setBounds(0, 0, width, bottom)
        glass.draw(c)
    }

    companion object {
        val INSET get() = u(8)
    }
}
