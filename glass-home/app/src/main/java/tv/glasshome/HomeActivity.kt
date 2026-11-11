package tv.glasshome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * The home screen: clock and quick actions on top, a strip of pinned favourites, a strip
 * of pinned websites, then every other app in a grid. Hold OK on anything for options.
 */
class HomeActivity : Activity() {

    private lateinit var store: Store
    private lateinit var scroll: CenterScrollView
    private lateinit var favScroll: CenterHScrollView
    private lateinit var favRow: RowLayout
    private lateinit var favHint: TextView
    private lateinit var siteRow: RowLayout
    private lateinit var grid: LinearLayout
    private lateinit var netPill: PillView

    private var apps: List<AppEntry> = emptyList()
    private var focusKey: String? = null
    private var focusInFavorites = false
    private var firstRender = true

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val side get() = u(96)
    private val favW get() = u(320)
    private val favH get() = u(180)
    private val columns = 6
    private val appW get() = (Dim.w - 2 * (side - TileView.PAD)) / columns - 2 * TileView.PAD
    private val appH get() = appW * 9 / 16

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadApps()
    }
    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateNetwork()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dim.init(this)
        store = Store(this)
        applyWallpaper()
        buildUi()
        render()
        loadApps()

        val pkg = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        @Suppress("DEPRECATION")
        val net = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, pkg, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(networkReceiver, net, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, pkg)
            registerReceiver(networkReceiver, net)
        }
    }

    override fun onResume() {
        super.onResume()
        immersive()
        updateNetwork()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        unregisterReceiver(networkReceiver)
        io.shutdown()
        super.onDestroy()
    }

    /** Home pressed while already home: jump back to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        goToTop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goToTop()

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            focusedTile()?.performLongClick() ?: launcherMenu()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun focusedTile(): TileView? = window.decorView.findFocus() as? TileView

    private fun goToTop() {
        val first = firstTile() ?: return
        first.requestFocus()
        favScroll.smoothScrollTo(0, 0)
        scroll.smoothScrollTo(0, 0)
    }

    private fun firstTile(): View? = when {
        favRow.childCount > 0 -> favRow.getChildAt(0)
        (grid.getChildAt(0) as? ViewGroup)?.childCount ?: 0 > 0 -> (grid.getChildAt(0) as ViewGroup).getChildAt(0)
        else -> siteRow.getChildAt(0)
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun applyWallpaper() {
        val d = BitmapDrawable(resources, Wallpapers.make(store.wallpaper))
        d.isFilterBitmap = true
        d.setDither(true)
        window.setBackgroundDrawable(d)
    }

    // ---------------------------------------------------------------- layout

    private fun label(text: String, size: Float, alpha: Float) = TextView(this).apply {
        this.text = text
        setTextColor(withAlpha(Color.WHITE, alpha))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(size))
        typeface = Fonts.medium
        includeFontPadding = false
        setShadowLayer(uf(8f), 0f, uf(1f), 0x55000000)
    }

    private fun header(text: String) = label(text, 30f, 0.92f).apply {
        setPadding(side, u(18), side, u(2))
    }

    private fun buildUi() {
        scroll = CenterScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, u(80))
        }
        scroll.addView(column, MATCH_PARENT, WRAP_CONTENT)

        // Top bar: clock on the left, quick actions on the right.
        val top = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(side, u(40), side - u(10), 0)
        }
        val clockColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockColumn.addView(TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(72f))
            typeface = Fonts.light
            includeFontPadding = false
            setShadowLayer(uf(10f), 0f, uf(2f), 0x55000000)
        })
        clockColumn.addView(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            setTextColor(withAlpha(Color.WHITE, 0.75f))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(26f))
            typeface = Fonts.regular
            includeFontPadding = false
            setPadding(u(4), u(6), 0, 0)
        })
        top.addView(clockColumn, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.TOP))

        val pills = RowLayout(this)
        netPill = PillView(this, Glyphs.OFFLINE, "").apply { setOnClickListener { openNetworkSettings() } }
        pills.addView(netPill)
        pills.addView(PillView(this, Glyphs.GLOBE, "Browse").apply { setOnClickListener { openBrowser(null, false) } })
        pills.addView(PillView(this, Glyphs.GEAR, "Settings").apply {
            setOnClickListener { launcherMenu() }
            setOnLongClickListener { openSystemSettings(); true }
        })
        top.addView(pills, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.TOP))
        column.addView(top, MATCH_PARENT, u(176))

        // Favourites.
        column.addView(header("Favorites"))
        favRow = RowLayout(this).apply { setPadding(side - TileView.PAD, 0, side - TileView.PAD, 0) }
        favScroll = CenterHScrollView(this)
        favScroll.addView(favRow, WRAP_CONTENT, WRAP_CONTENT)
        column.addView(favScroll, MATCH_PARENT, WRAP_CONTENT)
        favHint = label("Hold OK on any app or website to pin it here.", 26f, 0.6f).apply {
            typeface = Fonts.regular
            setPadding(side, u(34), side, u(34))
        }
        column.addView(favHint)

        // Websites.
        column.addView(header("Websites"))
        siteRow = RowLayout(this).apply { setPadding(side - TileView.PAD, 0, side - TileView.PAD, 0) }
        val siteScroll = CenterHScrollView(this)
        siteScroll.addView(siteRow, WRAP_CONTENT, WRAP_CONTENT)
        column.addView(siteScroll, MATCH_PARENT, WRAP_CONTENT)

        // Everything else.
        column.addView(header("Apps"))
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(side - TileView.PAD, 0, side - TileView.PAD, 0)
        }
        column.addView(grid, MATCH_PARENT, WRAP_CONTENT)

        val root = FrameLayout(this)
        root.addView(scroll, MATCH_PARENT, MATCH_PARENT)
        setContentView(root)
    }

    // ---------------------------------------------------------------- data

    private fun loadApps() {
        val bw = favW
        val bh = favH
        val iconSize = u(112)
        io.execute {
            val list = try {
                Apps.load(applicationContext, bw, bh, iconSize)
            } catch (e: Exception) {
                emptyList()
            }
            main.post {
                if (isDestroyed) return@post
                apps = list
                seedIfNeeded()
                render()
            }
        }
    }

    /** First run: pin the streaming apps that are installed and add a couple of sites. */
    private fun seedIfNeeded() {
        if (store.seeded || apps.isEmpty()) return
        val byPackage = apps.associateBy { it.pkg }
        store.favorites = Apps.STARTER_PACKAGES.mapNotNull { byPackage[it]?.key }.take(6)
        if (store.sites.isEmpty()) {
            store.addSite("YouTube TV", "https://www.youtube.com/tv", true)
            store.addSite("Google", "https://www.google.com", false)
        }
        store.seeded = true
    }

    private fun render() {
        val current = focusedTile()
        val wantKey = focusKey ?: current?.key
        val wantFav = if (focusKey != null) focusInFavorites else current?.parent === favRow
        focusKey = null

        val appByKey = apps.associateBy { it.key }
        val sites = store.sites
        val siteByKey = sites.associateBy { it.key }
        val hidden = store.hidden

        favRow.removeAllViews()
        for (key in store.favorites) {
            val app = appByKey[key]
            val site = siteByKey[key]
            if (app != null && key !in hidden) favRow.addView(appTile(app, favW, favH, true))
            else if (site != null) favRow.addView(siteTile(site, favW, favH, true))
        }
        val empty = favRow.childCount == 0
        favHint.visibility = if (empty) View.VISIBLE else View.GONE
        favScroll.visibility = if (empty) View.GONE else View.VISIBLE

        siteRow.removeAllViews()
        for (site in sites) siteRow.addView(siteTile(site, appW, appH, false))
        siteRow.addView(TileView(this, appW, appH).apply {
            key = "action:add-site"
            label = "Add website"
            glyph = Glyphs.PLUS
            setOnClickListener { editSite(null) }
        })

        grid.removeAllViews()
        for (chunk in apps.filter { it.key !in hidden }.chunked(columns)) {
            val row = RowLayout(this)
            for (app in chunk) row.addView(appTile(app, appW, appH, false))
            grid.addView(row, WRAP_CONTENT, WRAP_CONTENT)
        }

        val target = (if (firstRender) null else findTile(wantKey, wantFav)) ?: firstTile()
        if (apps.isNotEmpty()) firstRender = false
        if (target != null) {
            // Also the fallback the system uses when it has to pick a highlight by itself.
            if (Build.VERSION.SDK_INT >= 26) target.isFocusedByDefault = true
            target.requestFocus()
        }
    }

    private fun findTile(key: String?, preferFavorites: Boolean): View? {
        if (key == null) return null
        fun search(group: ViewGroup): View? {
            for (i in 0 until group.childCount) {
                val c = group.getChildAt(i)
                if (c is TileView && c.key == key) return c
                if (c is ViewGroup) search(c)?.let { return it }
            }
            return null
        }
        val inFav = search(favRow)
        val elsewhere = search(siteRow) ?: search(grid)
        return if (preferFavorites) inFav ?: elsewhere else elsewhere ?: inFav
    }

    private fun appTile(app: AppEntry, w: Int, h: Int, inFavorites: Boolean) = TileView(this, w, h).apply {
        key = app.key
        label = app.label
        icon = app.icon
        banner = app.banner
        tint = app.tint
        if (app.icon == null && app.banner == null) monogram = app.label.take(1).uppercase()
        setOnClickListener { launch(app) }
        setOnLongClickListener { appMenu(app, inFavorites); true }
    }

    private fun siteTile(site: Site, w: Int, h: Int, inFavorites: Boolean) = TileView(this, w, h).apply {
        key = site.key
        label = site.name
        monogram = site.monogram
        tint = Tint.fromText(site.host)
        setOnClickListener { openBrowser(site.url, site.tvMode) }
        setOnLongClickListener { siteMenu(site, inFavorites); true }
    }

    // ---------------------------------------------------------------- actions

    private fun start(intent: Intent, fallback: Intent? = null) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            if (fallback != null) start(fallback) else toast("This TV has no screen for that.")
        } catch (e: SecurityException) {
            toast("Not allowed to open that.")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun launch(app: AppEntry) {
        val category = if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        start(
            Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }

    private fun openBrowser(url: String?, tvMode: Boolean) {
        val i = Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (url != null) i.putExtra(BrowserActivity.EXTRA_URL, url)
        i.putExtra(BrowserActivity.EXTRA_TV_MODE, tvMode)
        start(i)
    }

    private fun openSystemSettings() = start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun openNetworkSettings() = start(
        Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    @Suppress("DEPRECATION")
    private fun updateNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = try {
            cm.activeNetworkInfo
        } catch (e: Exception) {
            null
        }
        netPill.glyph = when {
            info == null || !info.isConnected -> Glyphs.OFFLINE
            info.type == ConnectivityManager.TYPE_ETHERNET -> Glyphs.WIRED
            else -> Glyphs.WIFI
        }
        netPill.text = if (info == null || !info.isConnected) "Offline" else ""
    }

    /** Re-render and put the highlight back on [key]. */
    private fun refresh(key: String?, inFavorites: Boolean) {
        focusKey = key
        focusInFavorites = inFavorites
        render()
    }

    private fun moveFavorite(key: String, delta: Int) {
        val all = store.favorites.toMutableList()
        // Only items currently on screen count as neighbours.
        val visible = (0 until favRow.childCount).map { (favRow.getChildAt(it) as TileView).key }
        val at = visible.indexOf(key)
        if (at < 0) return
        val to = if (delta == Int.MIN_VALUE) 0 else (at + delta).coerceIn(0, visible.size - 1)
        if (to == at) return
        val anchor = visible[to]
        all.remove(key)
        val anchorIndex = all.indexOf(anchor)
        all.add(if (to > at) anchorIndex + 1 else anchorIndex, key)
        store.favorites = all
        refresh(key, true)
    }

    private fun addFavoriteRows(d: GlassDialog, key: String, inFavorites: Boolean) {
        if (store.isFavorite(key)) {
            if (inFavorites) {
                d.row("Move left") { moveFavorite(key, -1) }
                d.row("Move right") { moveFavorite(key, 1) }
                d.row("Move to front") { moveFavorite(key, Int.MIN_VALUE) }
            }
            d.row("Remove from Favorites") {
                store.toggleFavorite(key)
                refresh(key, false)
            }
        } else {
            d.row("Add to Favorites") {
                store.toggleFavorite(key)
                refresh(key, false)
            }
        }
    }

    private fun appMenu(app: AppEntry, inFavorites: Boolean) {
        val d = GlassDialog(this, app.label, app.pkg)
        d.row("Open") { launch(app) }
        addFavoriteRows(d, app.key, inFavorites)
        d.row("Hide from home") {
            store.hidden = store.hidden + app.key
            store.favorites = store.favorites - app.key
            toast("Hidden. Bring it back from Settings > Hidden apps.")
            refresh(null, false)
        }
        d.row("App info") {
            start(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        d.row("Uninstall", danger = true) {
            @Suppress("DEPRECATION")
            start(
                Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${app.pkg}")),
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")),
            )
        }
        d.show()
    }

    private fun siteMenu(site: Site, inFavorites: Boolean) {
        val d = GlassDialog(this, site.name, site.url)
        d.row("Open") { openBrowser(site.url, site.tvMode) }
        addFavoriteRows(d, site.key, inFavorites)
        d.row("Edit") { editSite(site) }
        d.row("Remove", danger = true) {
            store.removeSite(site)
            refresh(null, false)
        }
        d.show()
    }

    private fun editSite(existing: Site?) {
        val d = GlassDialog(
            this, if (existing == null) "Add website" else "Edit website",
            "Press OK on a field to type."
        )
        val url = d.input("Address, e.g. bbc.com", existing?.url ?: "", uri = true)
        val name = d.input("Name (optional)", existing?.name ?: "")
        var tv = existing?.tvMode ?: false
        d.row({ if (tv) "Remote: arrow keys go to the page (TV sites)" else "Remote: moves an on-screen pointer" }, keepOpen = true) {
            tv = !tv
        }
        d.row("Save") {
            val address = Store.normalizeUrl(url.text.toString())
            if (address.isEmpty()) {
                toast("No address entered.")
                return@row
            }
            val host = (Uri.parse(address).host ?: address).removePrefix("www.")
            val title = name.text.toString().trim().ifEmpty {
                host.substringBeforeLast('.').replaceFirstChar { it.uppercase() }
            }
            val key = if (existing == null) {
                store.addSite(title, address, tv).key
            } else {
                existing.name = title
                existing.url = address
                existing.tvMode = tv
                store.updateSite(existing)
                existing.key
            }
            refresh(key, false)
        }
        d.show()
    }

    private fun launcherMenu() {
        val d = GlassDialog(this, "Glass Home", "Hold OK on any tile for its options.")
        d.row("TV settings") { openSystemSettings() }
        d.row({ "Wallpaper: ${Wallpapers.name(store.wallpaper)}" }, keepOpen = true) {
            store.wallpaper = (store.wallpaper + 1) % Wallpapers.count
            applyWallpaper()
        }
        val hiddenCount = store.hidden.size
        if (hiddenCount > 0) d.row("Hidden apps ($hiddenCount)") { hiddenMenu() }
        d.row("Make this the default home screen") {
            start(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        d.show()
    }

    private fun hiddenMenu() {
        val hidden = store.hidden
        val byKey = apps.associateBy { it.key }
        val d = GlassDialog(this, "Hidden apps", "Choose one to show it on the home screen again.")
        for (key in hidden.sortedBy { byKey[it]?.label?.lowercase() ?: it }) {
            d.row(byKey[key]?.label ?: key.removePrefix("app:")) {
                store.hidden = store.hidden - key
                refresh(key, false)
            }
        }
        d.show()
    }
}
