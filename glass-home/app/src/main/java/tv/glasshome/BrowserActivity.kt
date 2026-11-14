package tv.glasshome

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

/**
 * A browser made for a TV remote.
 *
 * Pointer mode (default): the arrow keys glide an on-screen pointer that speeds up while
 * held, OK clicks, pushing against a screen edge scrolls, Back goes to the previous page.
 * Direct mode: for sites built for TVs (e.g. youtube.com/tv) the keys go straight to the page.
 * Hold OK, hold Back, or press Menu for the browser menu.
 */
class BrowserActivity : Activity() {

    private lateinit var store: Store
    private lateinit var root: FrameLayout
    internal lateinit var web: WebView
        private set
    private lateinit var pointer: PointerView
    private lateinit var chip: TextView
    private lateinit var progress: View

    internal var tvMode = false
        private set
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    internal var px = 0f
        private set
    internal var py = 0f
        private set
    private var dirX = 0
    private var dirY = 0
    private var moving = false
    private var heldSince = 0L
    private var lastFrame = 0L
    private var lastWheel = 0L

    private var okLong = false
    private var backLong = false
    private val handler = Handler(Looper.getMainLooper())
    private val okLongPress = Runnable {
        okLong = true
        showMenu()
    }
    private val backLongPress = Runnable {
        backLong = true
        showMenu()
    }
    private val hideChip = Runnable { chip.animate().alpha(0f).setDuration(400).start() }
    private val hidePointer = Runnable { pointer.animate().alpha(0f).setDuration(500).start() }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) = step(now)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dim.init(this)
        Fonts.init(this)
        store = Store(this)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        web = WebView(this)
        root.addView(web, MATCH_PARENT, MATCH_PARENT)

        progress = View(this).apply {
            setBackgroundColor(0xFF7AA2FF.toInt())
            pivotX = 0f
            scaleX = 0f
        }
        root.addView(progress, FrameLayout.LayoutParams(MATCH_PARENT, u(4), Gravity.TOP))

        chip = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(24f))
            typeface = Fonts.medium
            isSingleLine = true
            maxWidth = u(1100)
            setPadding(u(30), u(16), u(30), u(16))
            background = GlassDrawable.panel(uf(30f))
            alpha = 0f
        }
        root.addView(
            chip,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = u(28) }
        )

        pointer = PointerView(this)
        root.addView(pointer, MATCH_PARENT, MATCH_PARENT)
        setContentView(root)

        px = Dim.w / 2f
        py = Dim.h / 2f
        pointer.moveTo(px, py)

        configureWeb()
        open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    private fun open(intent: Intent) {
        val url = intent.dataString ?: intent.getStringExtra(EXTRA_URL)
        setTvMode(intent.getBooleanExtra(EXTRA_TV_MODE, false), announce = false)
        if (url.isNullOrEmpty()) {
            if (web.url == null) web.loadDataWithBaseURL(null, START_PAGE, "text/html", "utf-8", null)
        } else if (url != web.url && url != web.originalUrl) {
            web.loadUrl(url)
        }
        if (!tvMode) flash("Arrows move the pointer  ·  OK clicks  ·  Hold OK for menu")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWeb() {
        web.setBackgroundColor(Color.BLACK)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        applyTextZoom()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") return false
                // App links (market://, intent://, mailto: ...) are handed to the system.
                try {
                    val i = if (scheme == "intent") {
                        Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME)
                    } else {
                        Intent(Intent.ACTION_VIEW, request.url)
                    }
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(this@BrowserActivity, "No app can open this link.", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                flash(hostOf(url))
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.scaleX = 0f
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.scaleX = if (newProgress >= 100) 0f else newProgress / 100f
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                view.setBackgroundColor(Color.BLACK)
                root.addView(view, root.indexOfChild(pointer), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }

            override fun onHideCustomView() = exitFullscreen()

            override fun onPermissionRequest(request: PermissionRequest) {
                // Only allow what protected video playback needs; never camera or microphone.
                val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (drm.isEmpty()) request.deny() else request.grant(drm.toTypedArray())
            }
        }
    }

    private fun exitFullscreen() {
        val v = fullscreenView ?: return
        root.removeView(v)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    private fun hostOf(url: String?): String {
        if (url == null || url.startsWith("data:") || url.startsWith("about:")) return "New page"
        return (Uri.parse(url).host ?: url).removePrefix("www.")
    }

    private fun flash(text: String) {
        chip.text = text
        chip.animate().cancel()
        chip.alpha = 1f
        handler.removeCallbacks(hideChip)
        handler.postDelayed(hideChip, 3500)
    }

    // ---------------------------------------------------------------- settings

    private fun chromeVersion(): String =
        Regex("Chrome/([0-9.]+)").find(WebSettings.getDefaultUserAgent(this))?.groupValues?.get(1) ?: "120.0.0.0"

    private fun applyUserAgent() {
        val v = chromeVersion()
        web.settings.userAgentString = when {
            // TV web apps decide what to serve from the user agent, so look like a smart TV.
            tvMode -> "Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$v Safari/537.36 WebAppManager"
            // Big screen: ask for full desktop pages instead of blown-up phone layouts.
            store.desktopSites -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$v Safari/537.36"
            else -> null
        }
    }

    private fun textZoom(): Int {
        val saved = store.textZoom
        if (saved > 0) return saved
        // Low-density TV panels give pages a huge viewport and tiny text; compensate.
        val cssWidth = Dim.w / resources.displayMetrics.density
        return (100 * cssWidth / 1280f).toInt().coerceIn(100, 170)
    }

    private fun applyTextZoom() {
        web.settings.textZoom = if (tvMode) 100 else textZoom()
    }

    private fun setTvMode(on: Boolean, announce: Boolean) {
        tvMode = on
        dirX = 0
        dirY = 0
        pointer.visibility = if (on) View.GONE else View.VISIBLE
        applyUserAgent()
        applyTextZoom()
        if (on) web.requestFocus() else showPointer()
        if (announce) flash(if (on) "Direct mode: remote keys go to the page" else "Pointer mode")
    }

    // ---------------------------------------------------------------- remote handling

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        // Keys typed on the on-screen keyboard are not remote presses; let them through.
        // (Only the flag is checked: phone "virtual remote" apps and adb also inject keys
        // without a real device id, and those must still drive the pointer.)
        val soft = (e.flags and KeyEvent.FLAG_SOFT_KEYBOARD) != 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (!down) showMenu()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (down) {
                    if (e.repeatCount == 0) {
                        backLong = false
                        handler.postDelayed(backLongPress, 650)
                    }
                } else {
                    handler.removeCallbacks(backLongPress)
                    if (!backLong) goBack()
                }
                return true
            }
        }
        if (tvMode || soft) return super.dispatchKeyEvent(e)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> return steer(-1, 0, down, e.repeatCount)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return steer(1, 0, down, e.repeatCount)
            KeyEvent.KEYCODE_DPAD_UP -> return steer(0, -1, down, e.repeatCount)
            KeyEvent.KEYCODE_DPAD_DOWN -> return steer(0, 1, down, e.repeatCount)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (down) {
                    if (e.repeatCount == 0) {
                        okLong = false
                        handler.postDelayed(okLongPress, 600)
                    }
                } else {
                    handler.removeCallbacks(okLongPress)
                    if (!okLong) click()
                }
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (down) web.pageUp(false)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (down) web.pageDown(false)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun goBack() {
        when {
            fullscreenView != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    private fun steer(x: Int, y: Int, down: Boolean, repeat: Int): Boolean {
        if (down) {
            if (x != 0) dirX = x
            if (y != 0) dirY = y
            showPointer()
            if (repeat == 0) nudge(x * uf(14f), y * uf(14f)) // a tap is a small, precise step
            if (!moving) {
                moving = true
                heldSince = System.nanoTime()
                lastFrame = heldSince
                Choreographer.getInstance().postFrameCallback(frame)
            }
        } else {
            if (x != 0 && dirX == x) dirX = 0
            if (y != 0 && dirY == y) dirY = 0
        }
        return true
    }

    private fun nudge(dx: Float, dy: Float) {
        px = (px + dx).coerceIn(0f, maxOf(root.width, 1) - 1f)
        py = (py + dy).coerceIn(0f, maxOf(root.height, 1) - 1f)
        pointer.moveTo(px, py)
    }

    /** One animation frame of pointer travel; scrolls the page when pushed against an edge. */
    private fun step(now: Long) {
        if (dirX == 0 && dirY == 0) {
            moving = false
            return
        }
        val dt = min((now - lastFrame) / 1e9f, 0.05f)
        lastFrame = now
        val held = (now - heldSince) / 1e9f
        val ramp = min(1f, held / 1.1f)
        val speed = uf(240f) + uf(1500f) * ramp * ramp
        val nx = px + dirX * speed * dt
        val ny = py + dirY * speed * dt
        val maxX = maxOf(root.width, 1) - 1f
        val maxY = maxOf(root.height, 1) - 1f
        var wheelX = 0f
        var wheelY = 0f
        if (ny < 0f) wheelY = 1f else if (ny > maxY) wheelY = -1f
        if (nx < 0f) wheelX = -1f else if (nx > maxX) wheelX = 1f
        px = nx.coerceIn(0f, maxX)
        py = ny.coerceIn(0f, maxY)
        pointer.moveTo(px, py)
        sendPointer(MotionEvent.ACTION_HOVER_MOVE, 0f, 0f)
        if ((wheelX != 0f || wheelY != 0f) && now - lastWheel > 45_000_000L) {
            lastWheel = now
            val strength = 0.6f + 1.6f * ramp
            sendPointer(MotionEvent.ACTION_SCROLL, wheelX * strength, wheelY * strength)
        }
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun target(): View = fullscreenView ?: web

    /** Hover / mouse-wheel events at the pointer, so menus open on hover and any pane scrolls. */
    private fun sendPointer(action: Int, wheelX: Float, wheelY: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = px
            y = py
            setAxisValue(MotionEvent.AXIS_HSCROLL, wheelX)
            setAxisValue(MotionEvent.AXIS_VSCROLL, wheelY)
        })
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        target().dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun click() {
        showPointer()
        pointer.pulse()
        val x = px
        val y = py
        val t = SystemClock.uptimeMillis()
        val v = target()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        v.dispatchTouchEvent(down)
        down.recycle()
        handler.postDelayed({
            val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            v.dispatchTouchEvent(up)
            up.recycle()
        }, 70)
    }

    private fun showPointer() {
        if (tvMode) return
        pointer.animate().cancel()
        pointer.alpha = 1f
        handler.removeCallbacks(hidePointer)
        handler.postDelayed(hidePointer, 6000)
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        dirX = 0
        dirY = 0
        val d = GlassDialog(this, web.title?.takeIf { it.isNotBlank() } ?: "Browser", hostOf(web.url))
        d.row("Search or enter address") { addressDialog() }
        d.row("Reload") { web.reload() }
        if (web.canGoForward()) d.row("Forward") { web.goForward() }
        d.row({ if (tvMode) "Switch to pointer mode" else "Switch to direct mode (TV sites)" }) {
            setTvMode(!tvMode, announce = true)
            web.reload()
        }
        if (!tvMode) {
            d.row({ "Text size: ${textZoom()}%  (press to enlarge)" }, keepOpen = true) {
                val next = textZoom() + 10
                store.textZoom = if (next > 200) 80 else next
                applyTextZoom()
            }
            d.row({ if (store.desktopSites) "Showing desktop sites" else "Showing mobile sites" }, keepOpen = true) {
                store.desktopSites = !store.desktopSites
                applyUserAgent()
                web.reload()
            }
        }
        val current = web.url
        if (current != null && current.startsWith("http")) {
            d.row("Pin this site to Home") {
                val name = web.title?.takeIf { it.isNotBlank() }?.take(24) ?: hostOf(current)
                store.addSite(name, current, tvMode)
                Toast.makeText(this, "Pinned. It appears under Websites.", Toast.LENGTH_SHORT).show()
            }
        }
        d.row("Close browser", danger = true) { finish() }
        d.show()
    }

    private fun addressDialog() {
        val d = GlassDialog(this, "Search or enter address", "Press OK on the field to type.")
        val current = web.url?.takeIf { it.startsWith("http") } ?: ""
        val field = d.input("Search the web or type a site", current, uri = true)
        d.row("Go") {
            val url = Store.normalizeUrl(field.text.toString())
            if (url.isNotEmpty()) web.loadUrl(url)
        }
        d.show()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        web.onResume()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onPause() {
        dirX = 0
        dirY = 0
        web.onPause() // stops video and audio when leaving for Home
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    /** A soft translucent dot rather than a desktop arrow: easier to follow from the sofa. */
    class PointerView(ctx: Context) : View(ctx) {
        private var cx = 0f
        private var cy = 0f
        private var pulse = 0f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = uf(3f)
        }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x99000000.toInt()
            strokeWidth = uf(2f)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6101424.toInt() }

        fun moveTo(x: Float, y: Float) {
            cx = x
            cy = y
            invalidate()
        }

        fun pulse() {
            pulse = 1f
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val r = uf(17f) * (1f - 0.3f * pulse)
            c.drawCircle(cx, cy, r, fill)
            c.drawCircle(cx, cy, r + uf(2.5f), outline)
            c.drawCircle(cx, cy, r, ring)
            c.drawCircle(cx, cy, uf(3f), dot)
            if (pulse > 0f) {
                pulse = (pulse - 0.08f).coerceAtLeast(0f)
                postInvalidateOnAnimation()
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TV_MODE = "tv"

        private const val START_PAGE = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html,body{height:100%;margin:0}
body{display:flex;align-items:center;justify-content:center;font-family:sans-serif;color:#fff;
background:radial-gradient(circle at 15% 15%,#2f6bff 0,transparent 55%),
radial-gradient(circle at 85% 10%,#8b5cf6 0,transparent 50%),
radial-gradient(circle at 75% 95%,#12b8a6 0,transparent 55%),#0a1030}
form{width:62%;text-align:center}
h1{font-weight:300;font-size:2.4em;margin:0 0 .8em}
input{box-sizing:border-box;width:100%;font-size:1.6em;padding:.7em 1.1em;border-radius:99px;
border:1.5px solid rgba(255,255,255,.45);background:rgba(255,255,255,.16);color:#fff;outline:none}
input::placeholder{color:rgba(255,255,255,.6)}
p{opacity:.65;font-size:1.1em;margin-top:1.4em}
</style></head><body>
<form action="https://www.google.com/search" method="get">
<h1>Where to?</h1>
<input name="q" placeholder="Search the web" autocomplete="off">
<p>Move the pointer onto the box and press OK to type. Hold OK for the menu.</p>
</form></body></html>"""
    }
}
