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
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.max
import kotlin.math.roundToInt

/** One switch for all decorative movement, for TVs too slow to enjoy it. */
object Motion {
    var reduced = false
}

/**
 * Shared focus behaviour. [lift] runs from 0 (resting) to 1 (focused) and springs a little
 * past 1 on the way in; [glow] is the same value held to 0..1 for opacities. Nothing here
 * moves unless the remote was just pressed: an idle screen draws no frames at all.
 */
abstract class FocusView(ctx: Context) : View(ctx) {
    protected var lift = 0f
    protected val glow get() = lift.coerceIn(0f, 1f)

    /** Runs 0..1 once each time focus arrives; drives the streak of light crossing a tile. */
    protected var sweep = 1f
    private var anim: ValueAnimator? = null
    private var sweepAnim: ValueAnimator? = null

    init {
        isFocusable = true
        isClickable = true
        // Plenty of generic TV firmwares boot in "touch mode" like a tablet, where ordinary
        // views refuse focus. A remote-first screen must always be able to show a highlight.
        isFocusableInTouchMode = true
        // We draw our own highlight; switch off the grey box Android 8+ adds by default.
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }

    private fun moved() {
        if (isFocused) onMotion?.invoke()
    }

    private fun refresh() {
        val on = isFocused || isHovered
        val target = if (on) 1f else 0f
        translationZ = if (on) uf(8f) else 0f
        anim?.cancel()
        sweepAnim?.cancel()
        sweep = 1f
        if (!isAttachedToWindow || Motion.reduced) {
            lift = target
            invalidate()
            moved()
            return
        }
        anim = ValueAnimator.ofFloat(lift, target).apply {
            duration = if (on) 340 else 200
            // Set separately: on Android 5.0 these two classes share no common parent.
            if (on) setInterpolator(OvershootInterpolator(2.4f)) else setInterpolator(DecelerateInterpolator(1.6f))
            addUpdateListener {
                lift = it.animatedValue as Float
                invalidate()
                moved()
            }
            start()
        }
        if (on) {
            sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 720
                startDelay = 90
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener {
                    sweep = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    /** A little shove and spring back: the answer to pressing a direction with nowhere to go. */
    fun bump(dx: Int, dy: Int) {
        if (Motion.reduced) return
        val d = uf(16f)
        animate().cancel()
        // A view's animator remembers its last start delay; the entrance leaves one behind.
        animate().setStartDelay(0).translationX(dx * d).translationY(dy * d).setDuration(90)
            .setInterpolator(DecelerateInterpolator()).setUpdateListener { moved() }
            .withEndAction {
                animate().translationX(0f).translationY(0f).setDuration(360)
                    .setInterpolator(OvershootInterpolator(3f)).setUpdateListener { moved() }.start()
            }.start()
    }

    // Lets the GPU fade a tile without first rendering it into an off-screen buffer.
    override fun hasOverlappingRendering(): Boolean = false

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
        sweepAnim?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        /** Called while the focused view is moving or growing, so the highlight can keep up. */
        var onMotion: (() -> Unit)? = null
    }
}

/**
 * One home-screen tile, in the manner of a tvOS app icon: a 16:9 piece of artwork with
 * rounded corners that sits quietly until focused, then springs toward you, catches a
 * streak of light, and shows its name. The bright ring around it is not drawn here: one
 * shared [FocusLens] glides from tile to tile instead.
 */
class TileView(
    ctx: Context,
    val cardW: Int,
    val cardH: Int,
    /** False in the dock, where the shelf above names the focused app instead. */
    private val caption: Boolean = true,
) : FocusView(ctx) {
    private val labelDrop = uf(42f)

    /** Picked up to be moved: held a little larger until it is put down. */
    var carried = false
        set(v) {
            field = v
            invalidate()
        }
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
    val radius = cardH * 0.16f
    private val rect = RectF()
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG)
    private var streakShader: LinearGradient? = null
    private val streakMatrix = Matrix()
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
        // A slanted band of light, a third of the tile wide, that is slid across on focus.
        streakShader = LinearGradient(
            0f, top, cardW * 0.42f, bottom,
            intArrayOf(0x00FFFFFF, 0x61FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP
        )
        streak.shader = streakShader
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

    private fun scale() = (1f + SCALE * lift + (if (carried) 0.07f else 0f)) * (if (isPressed) 0.96f else 1f)

    /** Where the card is right now, in this view's own coordinates, growth included. */
    fun cardRect(out: RectF) {
        val s = scale()
        val cx = width / 2f
        val cy = pad + cardH / 2f
        out.set(cx - cardW * s / 2f, cy - cardH * s / 2f, cx + cardW * s / 2f, cy + cardH * s / 2f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(cardW + pad * 2, cardH + pad * 2 + (if (caption) LABEL_H else 0))
    }

    override fun onDraw(c: Canvas) {
        if (!prepared) prepare()
        val cx = width / 2f
        val cy = pad + cardH / 2f
        val scale = scale()
        val l = pad.toFloat()
        val t = pad.toFloat()

        c.save()
        c.scale(scale, scale, cx, cy)
        val solid = art != null
        // Resting: a quiet contact shadow. Focused: the tile leaves the surface, so the shadow drops away.
        Shadow.draw(c, l, t, cardW, cardH, radius, uf(12f), Color.BLACK, lerp(0.42f, 0f, glow), uf(7f), !solid)
        Shadow.draw(c, l, t, cardW, cardH, radius, uf(34f), Color.BLACK, 0.6f * glow, uf(26f), !solid)

        rect.set(l, t, l + cardW, t + cardH)
        if (solid) {
            c.drawRoundRect(rect, radius, radius, artPaint)
            c.drawRoundRect(rect, radius, radius, sheen)
        } else {
            glass.setBounds(pad, pad, pad + cardW, pad + cardH)
            glass.focus = glow
            glass.draw(c)
            glyphPaint.alpha = (lerp(0.7f, 1f, glow) * 255).roundToInt()
            Glyphs.draw(c, glyph, cx, cy, cardH * 0.3f, glyphPaint)
        }
        if (sweep < 1f) {
            streakMatrix.setTranslate(l + lerp(-cardW * 0.5f, cardW * 1.1f, sweep), 0f)
            streakShader?.setLocalMatrix(streakMatrix)
            streak.alpha = (255 * kotlin.math.sin(Math.PI * sweep)).roundToInt().coerceIn(0, 255)
            c.drawRoundRect(rect, radius, radius, streak)
        }
        if (solid && glow < 1f) {
            val half = edge.strokeWidth / 2f
            rect.inset(half, half)
            edge.alpha = (255 * (1f - glow)).roundToInt()
            c.drawRoundRect(rect, radius - half, radius - half, edge)
        }
        c.restore()

        // Like tvOS, only the focused tile is captioned.
        if (caption && glow > 0.01f) {
            val text = fitted ?: TextUtils.ellipsize(
                label, labelPaint, cardW * 1.25f, TextUtils.TruncateAt.END
            ).toString().also { fitted = it }
            labelPaint.alpha = (255 * glow).roundToInt()
            val grow = (scale - 1f) * cardH / 2f
            c.drawText(text, cx, pad + cardH + grow + labelDrop - uf(8f) * (1f - glow), labelPaint)
        }
    }

    companion object {
        const val SCALE = 0.1f
        val PAD get() = u(18)
        val LABEL_H get() = u(50)
    }
}

/**
 * The single highlight on the home screen: a bright ring with light in the focused tile's
 * colour spilling around it. It does not jump between tiles - it glides, its leading edge
 * racing ahead and its trailing edge catching up, so it stretches like a drop of liquid
 * and settles on the new tile. One view, a few draw calls, and only while it is moving.
 */
class FocusLens(ctx: Context) : View(ctx) {
    private var target: TileView? = null
    private val from = RectF()
    private val to = RectF()
    private val cur = RectF()

    /** Where the ring was last drawn, in this view's coordinates. */
    internal val drawn: RectF get() = cur
    private var fromTint = Tint.DEFAULT
    private var travel = 1f
    private var presence = 0f
    private var travelAnim: ValueAnimator? = null
    private var presenceAnim: ValueAnimator? = null
    private val origin = Rect()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    init {
        isFocusable = false
        isClickable = false
    }

    /** Move to [tile], or fade away when focus has left the tiles (null). */
    fun follow(tile: TileView?) {
        if (tile === target) return
        val visible = presence > 0.01f && target != null
        if (visible) from.set(cur)
        fromTint = target?.tint ?: tile?.tint ?: Tint.DEFAULT
        if (tile != null) target = tile
        travelAnim?.cancel()
        if (tile == null) {
            fade(0f)
            return
        }
        if (!visible || Motion.reduced || !isAttachedToWindow) {
            travel = 1f
        } else {
            travel = 0f
            travelAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300
                addUpdateListener {
                    travel = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        fade(1f)
        invalidate()
    }

    private fun fade(goal: Float) {
        presenceAnim?.cancel()
        if (Motion.reduced || !isAttachedToWindow) {
            presence = goal
            if (goal == 0f) target = null
            invalidate()
            return
        }
        presenceAnim = ValueAnimator.ofFloat(presence, goal).apply {
            duration = 180
            addUpdateListener {
                presence = it.animatedValue as Float
                if (presence == 0f && goal == 0f) target = null
                invalidate()
            }
            start()
        }
    }

    /** The target's card in this view's coordinates, wherever scrolling and animation have put it. */
    private fun locate(tile: TileView, out: RectF): Boolean {
        val host = parent as? android.view.ViewGroup ?: return false
        if (!tile.isAttachedToWindow) return false
        origin.set(0, 0, 0, 0)
        try {
            host.offsetDescendantRectToMyCoords(tile, origin)
        } catch (e: IllegalArgumentException) {
            return false // not under the same parent
        }
        var dx = 0f
        var dy = 0f
        var v: View? = tile
        while (v != null && v !== host) {
            dx += v.translationX
            dy += v.translationY
            v = v.parent as? View
        }
        tile.cardRect(out)
        out.offset(origin.left + dx, origin.top + dy)
        return true
    }

    private fun lead(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u * u
    }

    private fun trail(t: Float) = t * t * (3f - 2f * t)

    override fun onDraw(c: Canvas) {
        val tile = target ?: return
        if (presence <= 0.01f || !locate(tile, to)) return
        if (travel >= 1f) {
            cur.set(to)
        } else {
            val right = to.centerX() >= from.centerX()
            val down = to.centerY() >= from.centerY()
            cur.left = lerp(from.left, to.left, if (right) trail(travel) else lead(travel))
            cur.right = lerp(from.right, to.right, if (right) lead(travel) else trail(travel))
            cur.top = lerp(from.top, to.top, if (down) trail(travel) else lead(travel))
            cur.bottom = lerp(from.bottom, to.bottom, if (down) lead(travel) else trail(travel))
        }
        val alpha = presence * tile.alpha
        val k = cur.width() / tile.cardW
        val radius = tile.radius * k

        // Light in the tile's colour, spilling onto its neighbours. Drawn from the tile's
        // own base size and stretched, so no new images are made while it moves.
        c.save()
        c.translate(cur.left, cur.top)
        c.scale(cur.width() / tile.cardW, cur.height() / tile.cardH)
        if (travel < 1f) {
            Shadow.draw(c, 0f, 0f, tile.cardW, tile.cardH, tile.radius, uf(38f), Tint.glow(fromTint), 0.75f * alpha * (1f - travel), uf(6f), true)
        }
        Shadow.draw(c, 0f, 0f, tile.cardW, tile.cardH, tile.radius, uf(38f), Tint.glow(tile.tint), 0.75f * alpha * travel, uf(6f), true)
        c.restore()

        val sw = uf(3.5f)
        ring.strokeWidth = sw
        ring.alpha = (240 * alpha).roundToInt().coerceIn(0, 255)
        cur.inset(sw / 2f, sw / 2f)
        c.drawRoundRect(cur, radius - sw / 2f, radius - sw / 2f, ring)
        cur.inset(-sw / 2f, -sw / 2f)
    }

    override fun onDetachedFromWindow() {
        travelAnim?.cancel()
        presenceAnim?.cancel()
        super.onDetachedFromWindow()
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
        Shadow.draw(c, o, o, size, size, size / 2f, uf(14f), Color.BLACK, lerp(0.28f, 0.5f, glow), uf(6f), true)
        Shadow.draw(c, o, o, size, size, size / 2f, uf(22f), Color.WHITE, 0.4f * glow, 0f, true)
        glass.setBounds(outer, outer, outer + size, outer + size)
        glass.focus = glow
        glass.draw(c)
        glyphPaint.color = blend(Color.WHITE, INK, glow)
        // The cog gives a sixth of a turn as it is selected.
        if (glyph == Glyphs.GEAR) c.rotate(60f * lift, cx, cy)
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

    /** Told the new scroll offset whenever the page moves. */
    var onScroll: ((Int) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScroll?.invoke(t)
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
        setPadding(INSET, INSET, INSET, INSET)
    }

    override fun onDraw(c: Canvas) {
        Shadow.draw(c, 0f, 0f, width, height, radius, uf(26f), Color.BLACK, 0.4f, uf(4f), true)
        glass.setBounds(0, 0, width, height)
        glass.draw(c)
    }

    companion object {
        val INSET get() = u(8)
    }
}

/**
 * The top shelf's headline: the name of whatever is focused in the dock, in large light
 * type. When it changes, the old name lifts away as the new one rises into its place.
 */
class ShelfTitle(ctx: Context) : View(ctx) {
    private var now: String? = null
    private var before: String? = null
    private var mix = 1f
    private var anim: ValueAnimator? = null
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Fonts.light
        textSize = uf(64f)
        letterSpacing = -0.015f
        setShadowLayer(uf(14f), 0f, uf(2f), 0x59000000)
    }

    val text: String? get() = now

    fun show(text: String?) {
        if (text == now) return
        before = now
        now = text
        anim?.cancel()
        if (Motion.reduced || !isAttachedToWindow) {
            mix = 1f
            invalidate()
            return
        }
        mix = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                mix = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val base = height - paint.descent() - uf(4f)
        val old = before
        if (old != null && mix < 1f) {
            paint.alpha = (255 * (1f - mix)).roundToInt()
            c.drawText(fit(old), 0f, base - uf(18f) * mix, paint)
        }
        now?.let {
            paint.alpha = (255 * mix).roundToInt()
            c.drawText(fit(it), 0f, base + uf(18f) * (1f - mix), paint)
        }
    }

    private fun fit(t: String) = TextUtils.ellipsize(t, paint, width.toFloat(), TextUtils.TruncateAt.END).toString()

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * The screensaver: the time, large and thin, over a dim wash of colour. Once a minute it
 * redraws with the new time somewhere else on the screen, so nothing sits in one place
 * long enough to mark the panel. Between those redraws it does no work at all.
 */
class ClockSaver(ctx: Context) : View(ctx) {
    var backdrop: Bitmap? = null
    private var fx = 0.5f
    private var fy = 0.5f
    private val image = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val time = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Fonts.thin
        textSize = uf(250f)
        letterSpacing = -0.02f
        fontFeatureSettings = "tnum"
    }
    private val date = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        typeface = Fonts.regular
        textSize = uf(38f)
    }

    val showing: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE
    }

    /** A new minute: move somewhere else and redraw. */
    fun tick() {
        fx = Math.random().toFloat()
        fy = Math.random().toFloat()
        invalidate()
    }

    fun show() {
        if (showing) return
        tick()
        animate().cancel()
        alpha = 0f
        visibility = VISIBLE
        if (Motion.reduced) alpha = 1f else animate().setStartDelay(0).alpha(1f).setDuration(900).start()
    }

    fun hide() {
        if (!showing) return
        animate().cancel()
        visibility = GONE
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        backdrop?.let {
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            image.alpha = 110
            c.drawBitmap(it, null, dst, image)
        }
        val moment = java.util.Date()
        val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        val clock = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(moment)
        val day = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(moment)
        val w = maxOf(time.measureText(clock), date.measureText(day))
        val blockH = uf(250f) + uf(60f)
        val margin = uf(110f)
        val x = margin + (width - 2 * margin - w).coerceAtLeast(0f) * fx
        val y = margin + (height - 2 * margin - blockH).coerceAtLeast(0f) * fy
        c.drawText(clock, x - uf(10f), y + uf(200f), time)
        c.drawText(day, x, y + uf(276f), date)
    }
}
