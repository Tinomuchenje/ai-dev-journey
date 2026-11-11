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
    private var first: View? = null
    private val blurTarget: View? = activity.findViewById(android.R.id.content)

    init {
        val root = FrameLayout(context)
        root.setOnClickListener { dismiss() }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable.panel(uf(40f))
            setPadding(u(36), u(34), u(36), u(30))
            isClickable = true
        }
        panel.addView(text(title, 34f, 1f).apply {
            typeface = Fonts.medium
            setPadding(u(12), 0, u(12), 0)
        })
        if (!subtitle.isNullOrEmpty()) {
            panel.addView(text(subtitle, 23f, 0.6f).apply { setPadding(u(12), u(4), u(12), 0) })
        }
        rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, u(18), 0, 0)
        }
        val scroller = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = MeasureSpec.makeMeasureSpec((Dim.h * 0.66f).toInt(), MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, cap)
            }
        }
        scroller.isVerticalScrollBarEnabled = false
        scroller.addView(rows, MATCH_PARENT, WRAP_CONTENT)
        panel.addView(scroller, MATCH_PARENT, WRAP_CONTENT)

        root.addView(panel, FrameLayout.LayoutParams(u(720), WRAP_CONTENT, Gravity.CENTER))
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
        val bg = GlassDrawable.row(uf(22f))
        val tv = text(label(), 28f, 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(28), 0, u(28), 0)
            background = bg
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isSingleLine = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            if (danger) setTextColor(0xFFFF8A8A.toInt())
            setOnFocusChangeListener { _, has -> bg.focus = if (has) 1f else 0f }
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
        val bg = GlassDrawable.row(uf(22f))
        val et = EditText(context).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(withAlpha(Color.WHITE, 0.45f))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(28f))
            typeface = Fonts.regular
            background = bg
            setPadding(u(28), 0, u(28), 0)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (uri) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_TEXT_FLAG_CAP_WORDS)
            setSelectAllOnFocus(true)
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            setOnFocusChangeListener { _, has -> bg.focus = if (has) 1f else 0f }
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
    }

    override fun dismiss() {
        if (Build.VERSION.SDK_INT >= 31) blurTarget?.setRenderEffect(null)
        if (isShowing && !activity.isDestroyed) super.dismiss()
    }
}
