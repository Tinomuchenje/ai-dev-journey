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
 * One home-screen tile: a tinted glass card (app banner, icon, website monogram or a
 * line glyph) with its name underneath. Drawn entirely in onDraw - one cheap display
 * list per tile, no nested layouts.
 */
class TileView(ctx: Context, private val cardW: Int, private val cardH: Int) : FocusView(ctx) {
    var key = ""
    var label = ""
        set(v) {
            field = v
            contentDescription = v
            fitted = null
        }
    var icon: Bitmap? = null
    var banner: Bitmap? = null
    var monogram: String? = null
    var glyph = Glyphs.NONE
    var tint = Tint.NEUTRAL

    private val pad = PAD
    private val radius = cardH * 0.17f
    private val rect = RectF()
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Fonts.medium
        textAlign = Paint.Align.CENTER
        textSize = cardH * 0.44f
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Fonts.medium
        textAlign = Paint.Align.CENTER
        textSize = uf(25f)
        setShadowLayer(uf(6f), 0f, uf(1f), 0x66000000)
    }
    private var fitted: String? = null
    private var prepared = false
    private val iconDst = RectF()

    private fun prepare() {
        prepared = true
        val top = pad.toFloat()
        val bottom = top + cardH
        body.shader = if (tint == Tint.NEUTRAL) {
            LinearGradient(0f, top, 0f, bottom, 0x30FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(
                0f, top, 0f, bottom,
                withAlpha(Tint.shade(tint, 1.15f), 0.7f), withAlpha(Tint.shade(tint, 0.7f), 0.56f),
                Shader.TileMode.CLAMP
            )
        }
        sheen.shader = LinearGradient(
            0f, top, 0f, top + cardH * 0.62f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        rim.shader = LinearGradient(
            pad.toFloat(), top, (pad + cardW).toFloat(), bottom,
            intArrayOf(Color.WHITE, 0x73FFFFFF, 0xBFFFFFFF.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        banner?.let { b ->
            val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix()
            m.setScale(cardW / b.width.toFloat(), cardH / b.height.toFloat())
            m.postTranslate(pad.toFloat(), top)
            shader.setLocalMatrix(m)
            bitmapPaint.shader = shader
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

        c.save()
        c.scale(scale, scale, cx, cy)
        if (lift > 0.01f) {
            val gp = Glow.pad()
            glowPaint.alpha = (150 * lift).roundToInt()
            c.drawBitmap(Glow.get(cardW, cardH, radius), (pad - gp).toFloat(), (pad - gp).toFloat(), glowPaint)
        }
        rect.set(pad.toFloat(), pad.toFloat(), (pad + cardW).toFloat(), (pad + cardH).toFloat())
        if (banner != null) {
            c.drawRoundRect(rect, radius, radius, bitmapPaint)
        } else {
            c.drawRoundRect(rect, radius, radius, body)
            val ic = icon
            val mono = monogram
            when {
                ic != null -> {
                    val s = cardH * 0.6f
                    iconDst.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
                    c.drawBitmap(ic, null, iconDst, bitmapPaint)
                }
                mono != null -> {
                    c.drawText(mono, cx, cy - (monoPaint.ascent() + monoPaint.descent()) / 2f, monoPaint)
                }
                glyph != Glyphs.NONE -> {
                    glyphPaint.alpha = (lerp(0.75f, 1f, lift) * 255).roundToInt()
                    Glyphs.draw(c, glyph, cx, cy, cardH * 0.34f, glyphPaint)
                }
            }
            c.drawRoundRect(rect, radius, radius, sheen)
        }
        val sw = lerp(uf(1.5f), uf(3.5f), lift)
        rim.strokeWidth = sw
        rim.alpha = (lerp(0.3f, 1f, lift) * 255).roundToInt()
        rect.inset(sw / 2f, sw / 2f)
        c.drawRoundRect(rect, radius - sw / 2f, radius - sw / 2f, rim)
        c.restore()

        val text = fitted ?: TextUtils.ellipsize(
            label, labelPaint, (cardW + pad).toFloat(), TextUtils.TruncateAt.END
        ).toString().also { fitted = it }
        labelPaint.alpha = (lerp(0.66f, 1f, lift) * 255).roundToInt()
        val grow = (scale - 1f) * cardH / 2f
        c.drawText(text, cx, pad + cardH + grow + uf(38f), labelPaint)
    }

    companion object {
        const val SCALE = 0.1f
        val PAD get() = u(16)
        val LABEL_H get() = u(54)
    }
}

/** Rounded glass button for the top bar: a glyph and an optional short label. */
class PillView(ctx: Context, glyph: Int, text: String) : FocusView(ctx) {
    var glyph = glyph
        set(v) {
            field = v
            invalidate()
        }
    var text = text
        set(v) {
            if (field == v) return
            field = v
            contentDescription = v
            requestLayout()
            invalidate()
        }

    private val outer = u(10)
    private val bodyH = u(72)
    private val padH = u(26)
    private val glyphSize = uf(30f)
    private val gap = u(14)
    private val glass = GlassDrawable(bodyH / 2f, 0x2EFFFFFF, 0x17FFFFFF)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Fonts.medium
        textSize = uf(25f)
    }

    init {
        contentDescription = text
    }

    private fun bodyW(): Int {
        val t = if (text.isEmpty()) 0 else gap + textPaint.measureText(text).roundToInt()
        return padH * 2 + glyphSize.roundToInt() + t
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(bodyW() + outer * 2, bodyH + outer * 2)
    }

    override fun onDraw(c: Canvas) {
        val w = width - outer * 2
        val cx = width / 2f
        val cy = height / 2f
        val scale = (1f + 0.07f * lift) * (if (isPressed) 0.97f else 1f)
        c.save()
        c.scale(scale, scale, cx, cy)
        if (lift > 0.01f) {
            val gp = Glow.pad()
            glowPaint.alpha = (130 * lift).roundToInt()
            c.drawBitmap(Glow.get(w, bodyH, bodyH / 2f), (outer - gp).toFloat(), (outer - gp).toFloat(), glowPaint)
        }
        glass.setBounds(outer, outer, outer + w, outer + bodyH)
        glass.focus = lift
        glass.draw(c)
        val gx = outer + padH + glyphSize / 2f
        Glyphs.draw(c, glyph, gx, cy, glyphSize, glyphPaint)
        if (text.isNotEmpty()) {
            c.drawText(
                text, gx + glyphSize / 2f + gap,
                cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint
            )
        }
        c.restore()
    }
}

/**
 * A horizontal strip of focusable children. Left/right never leaves the strip, so the
 * highlight cannot fly off diagonally to some unrelated row when a strip ends.
 */
class RowLayout(ctx: Context) : LinearLayout(ctx) {
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
