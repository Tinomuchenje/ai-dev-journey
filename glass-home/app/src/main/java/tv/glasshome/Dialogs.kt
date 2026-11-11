package tv.glasshome

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The one dialog style used everywhere: a dark glass panel in the middle of the screen
 * with a title and a column of big, remote-friendly rows.
 */
class GlassDialog(
    private val activity: Activity,
    title: String,
    subtitle: String? = null,
) : Dialog(activity, R.style.GlassDialog) {

    private val rows: LinearLayout
    private val panel: LinearLayout
    private var first: View? = null
    private val blurTarget: View? = activity.findViewById(android.R.id.content)

    init {
        val root = FrameLayout(context)
        root.setOnClickListener { dismiss() }

        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable.panel(uf(48f))
            setPadding(u(40), u(40), u(40), u(36))
            isClickable = true
        }
        panel.addView(text(title, 38f, 1f).apply {
            typeface = Fonts.medium
            setPadding(u(12), 0, u(12), 0)
        })
        if (!subtitle.isNullOrEmpty()) {
            panel.addView(text(subtitle, 23f, 0.6f).apply { setPadding(u(12), u(4), u(12), 0) })
        }
        rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(u(8), u(18), u(8), u(6))
        }
        val scroller = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = MeasureSpec.makeMeasureSpec((Dim.h * 0.66f).toInt(), MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, cap)
            }
        }
        scroller.isVerticalScrollBarEnabled = false
        scroller.clipChildren = false
        scroller.clipToPadding = false
        scroller.addView(rows, MATCH_PARENT, WRAP_CONTENT)
        panel.addView(scroller, MATCH_PARENT, WRAP_CONTENT)

        root.addView(panel, FrameLayout.LayoutParams(u(760), WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        window?.setLayout(MATCH_PARENT, MATCH_PARENT)
    }

    private fun text(value: String, size: Float, alpha: Float) = TextView(context).apply {
        text = value
        setTextColor(withAlpha(Color.WHITE, alpha))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(size))
        typeface = Fonts.regular
        includeFontPadding = false
    }

    private fun addRow(v: View, height: Int) {
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, height)
        lp.topMargin = u(10)
        rows.addView(v, lp)
        if (first == null) first = v
    }

    /**
     * Adds a selectable row. [label] is re-read after every press, so rows that toggle a
     * setting can stay open ([keepOpen]) and show the new value.
     */
    fun row(label: () -> String, keepOpen: Boolean = false, danger: Boolean = false, action: () -> Unit): GlassDialog {
        val bg = GlassDrawable.row(uf(24f))
        val idle = if (danger) 0xFFFF8A8A.toInt() else Color.WHITE
        val lit = if (danger) 0xFFB3261E.toInt() else INK
        val tv = text(label(), 28f, 1f).apply {
            typeface = Fonts.medium
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(30), 0, u(30), 0)
            background = bg
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isSingleLine = true
            setTextColor(idle)
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            setOnFocusChangeListener { v, has ->
                bg.focus = if (has) 1f else 0f
                (v as TextView).setTextColor(if (has) lit else idle)
                v.animate().scaleX(if (has) 1.025f else 1f).scaleY(if (has) 1.025f else 1f).setDuration(140).start()
            }
            setOnHoverListener { v, e ->
                if (e.action == android.view.MotionEvent.ACTION_HOVER_ENTER) v.requestFocus()
                false
            }
        }
        tv.setOnClickListener {
            if (!keepOpen) dismiss()
            action()
            if (keepOpen) tv.text = label()
        }
        addRow(tv, u(78))
        return this
    }

    fun row(label: String, danger: Boolean = false, action: () -> Unit) =
        row({ label }, false, danger, action)

    /** Adds a text field. OK on the remote opens the on-screen keyboard. */
    fun input(hint: String, value: String, uri: Boolean = false): EditText {
        val bg = GlassDrawable.row(uf(24f))
        val et = EditText(context).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(withAlpha(Color.WHITE, 0.45f))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(28f))
            typeface = Fonts.regular
            background = bg
            setPadding(u(30), 0, u(30), 0)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (uri) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_TEXT_FLAG_CAP_WORDS)
            setSelectAllOnFocus(true)
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            setOnFocusChangeListener { v, has ->
                bg.focus = if (has) 1f else 0f
                (v as EditText).setTextColor(if (has) INK else Color.WHITE)
                v.setHintTextColor(if (has) withAlpha(INK, 0.5f) else withAlpha(Color.WHITE, 0.45f))
            }
        }
        addRow(et, u(78))
        return et
    }

    override fun show() {
        super.show()
        // Real frosted glass where the OS can do it (Android 12+): blur the screen behind us.
        if (Build.VERSION.SDK_INT >= 31) {
            blurTarget?.setRenderEffect(RenderEffect.createBlurEffect(uf(28f), uf(28f), Shader.TileMode.CLAMP))
        }
        first?.requestFocus()
        if (Motion.reduced) return
        // The pane springs up, and its rows follow a beat apart.
        panel.alpha = 0f
        panel.scaleX = 0.92f
        panel.scaleY = 0.92f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
        for (i in 0 until rows.childCount) {
            val row = rows.getChildAt(i)
            row.alpha = 0f
            row.translationY = uf(18f)
            row.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 35).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
    }

    override fun dismiss() {
        if (Build.VERSION.SDK_INT >= 31) blurTarget?.setRenderEffect(null)
        if (isShowing && !activity.isDestroyed) super.dismiss()
    }
}
