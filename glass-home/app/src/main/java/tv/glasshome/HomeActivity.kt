package tv.glasshome

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
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
 * The home screen, laid out like a tvOS one: a large clock, a glass dock of favourites,
 * then every other app in a grid of 16:9 artwork, then pinned websites. The backdrop is
 * lit in the colour of whatever is focused. Hold OK on anything for its options.
 */
class HomeActivity : Activity() {

    private lateinit var store: Store
    private lateinit var backdrop: BackdropView
    private lateinit var scroll: CenterScrollView
    private lateinit var dockHolder: FrameLayout
    private lateinit var dock: DockLayout
    private lateinit var dockHint: TextView
    private lateinit var siteRow: RowLayout
    private lateinit var grid: LinearLayout
    private lateinit var netChip: ChipView

    private var apps: List<AppEntry> = emptyList()
    private val siteArt = HashMap<String, Bitmap>()
    private var focusKey: String? = null
    private var focusInDock = false
    private var firstRender = true
    private var ambientTint = Tint.DEFAULT

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val side get() = u(96)
    private val columns = 6
    private val appW get() = (Dim.w - 2 * (side - TileView.PAD)) / columns - 2 * TileView.PAD
    private val appH get() = appW * 9 / 16

    /** Artwork is rendered once at the largest size it is ever shown. */
    private val artW get() = u(310)
    private val artH get() = u(174)

    /** Dock tiles shrink as the dock fills, like a desktop dock, instead of scrolling. */
    private fun dockCardWidth(count: Int): Int {
        val room = Dim.w - 2 * u(70) - 2 * DockLayout.INSET
        val slot = minOf(artW + 2 * TileView.PAD, room / maxOf(count, 1))
        return slot - 2 * TileView.PAD
    }

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
        buildUi()
        applyWallpaper(animate = false)
        render()
        loadApps()
        // The room takes on the colour of whatever is focused.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (now is TileView) {
                ambientTint = now.tint
                applyWallpaper(animate = true)
            }
        }

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
        scroll.smoothScrollTo(0, 0)
    }

    private fun firstTile(): View? = when {
        dock.childCount > 0 -> dock.getChildAt(0)
        ((grid.getChildAt(0) as? ViewGroup)?.childCount ?: 0) > 0 -> (grid.getChildAt(0) as ViewGroup).getChildAt(0)
        else -> siteRow.getChildAt(0)
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private val stills = HashMap<Int, Bitmap>()

    private fun applyWallpaper(animate: Boolean) {
        val index = store.wallpaper
        val image = if (Wallpapers.isAmbient(index)) Wallpapers.ambient(ambientTint)
        else stills.getOrPut(index) { Wallpapers.make(index) }
        backdrop.show(image, animate)
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

    private fun header(text: String) = label(text, 30f, 0.95f).apply {
        setPadding(side, u(26), side, u(4))
    }

    private fun buildUi() {
        backdrop = BackdropView(this)
        scroll = CenterScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, u(90))
        }
        scroll.addView(column, MATCH_PARENT, WRAP_CONTENT)

        // Top of the screen: a big quiet clock, and three round glass buttons.
        val top = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(side, u(58), side - u(12), 0)
        }
        val clockColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockColumn.addView(TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(148f))
            typeface = Fonts.thin
            includeFontPadding = false
            letterSpacing = -0.02f
            setShadowLayer(uf(18f), 0f, uf(3f), 0x40000000)
        })
        clockColumn.addView(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            setTextColor(withAlpha(Color.WHITE, 0.7f))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(30f))
            typeface = Fonts.regular
            includeFontPadding = false
            setPadding(u(8), u(4), 0, 0)
        })
        top.addView(clockColumn, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.TOP))

        val chips = RowLayout(this)
        netChip = ChipView(this, Glyphs.OFFLINE, "Network").apply { setOnClickListener { openNetworkSettings() } }
        chips.addView(netChip)
        chips.addView(ChipView(this, Glyphs.GLOBE, "Browser").apply { setOnClickListener { openBrowserHome() } })
        chips.addView(ChipView(this, Glyphs.GEAR, "Settings").apply {
            setOnClickListener { launcherMenu() }
            setOnLongClickListener { openSystemSettings(); true }
        })
        top.addView(chips, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.TOP))
        column.addView(top, MATCH_PARENT, u(318))

        // The dock.
        dockHolder = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        dock = DockLayout(this)
        dockHolder.addView(dock, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        dockHint = label("Hold OK on any app to keep it here in the dock.", 28f, 0.6f).apply {
            typeface = Fonts.regular
            gravity = Gravity.CENTER
            setPadding(side, u(70), side, u(70))
        }
        dockHolder.addView(dockHint, MATCH_PARENT, WRAP_CONTENT)
        column.addView(dockHolder, MATCH_PARENT, WRAP_CONTENT)

        // Everything else. No heading: like tvOS, the grid simply continues below the dock.
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(side - TileView.PAD, u(22), side - TileView.PAD, 0)
        }
        column.addView(grid, MATCH_PARENT, WRAP_CONTENT)

        // Websites.
        column.addView(header("Websites"))
        siteRow = RowLayout(this).apply { setPadding(side - TileView.PAD, 0, side - TileView.PAD, 0) }
        val siteScroll = CenterHScrollView(this)
        siteScroll.addView(siteRow, WRAP_CONTENT, WRAP_CONTENT)
        column.addView(siteScroll, MATCH_PARENT, WRAP_CONTENT)

        val root = FrameLayout(this)
        root.addView(backdrop, MATCH_PARENT, MATCH_PARENT)
        root.addView(scroll, MATCH_PARENT, MATCH_PARENT)
        setContentView(root)
    }

    // ---------------------------------------------------------------- data

    private fun loadApps() {
        val w = artW
        val h = artH
        val iconSize = u(128)
        io.execute {
            val list = try {
                Apps.load(applicationContext, w, h, iconSize)
            } catch (e: Exception) {
                emptyList()
            }
            main.post {
                if (isDestroyed) return@post
                apps = list
                seedIfNeeded()
                render()
                fetchSiteIcons()
            }
        }
    }

    /** First run: put the streaming apps that are installed in the dock and add a couple of sites. */
    private fun seedIfNeeded() {
        if (store.seeded || apps.isEmpty()) return
        val byPackage = apps.associateBy { it.pkg }
        store.favorites = Apps.STARTER_PACKAGES.mapNotNull { byPackage[it]?.key }.take(5)
        if (store.sites.isEmpty()) {
            store.addSite("YouTube TV", "https://www.youtube.com/tv", true)
            store.addSite("Google", "https://www.google.com", false)
        }
        store.seeded = true
    }

    /** Artwork for a pinned site: its real icon when we have it, otherwise its initial. */
    private fun artFor(site: Site): Bitmap = siteArt.getOrPut(site.id) {
        val icon = SiteIcons.cached(this, site)
        if (icon != null) Art.tile(icon, artW, artH) else Art.monogram(site.monogram, Tint.fromText(site.host), artW, artH)
    }

    private fun fetchSiteIcons() {
        val sites = store.sites
        io.execute {
            var changed = false
            for (site in sites) {
                if (SiteIcons.fetch(applicationContext, site)) {
                    changed = true
                    main.post { siteArt.remove(site.id) }
                }
            }
            if (changed) main.post { if (!isDestroyed) render() }
        }
    }

    private fun render() {
        val current = focusedTile()
        val wantKey = focusKey ?: current?.key
        val wantDock = if (focusKey != null) focusInDock else current?.parent === dock
        focusKey = null

        val appByKey = apps.associateBy { it.key }
        val sites = store.sites
        val siteByKey = sites.associateBy { it.key }
        val hidden = store.hidden

        // Dock: tiles shrink to fit rather than scroll.
        val docked = store.favorites.filter { (appByKey[it] != null && it !in hidden) || siteByKey[it] != null }
            .take(MAX_DOCK)
        val dw = dockCardWidth(docked.size)
        val dh = dw * 9 / 16
        val drop = uf(42f) + TileView.PAD + DockLayout.INSET
        dock.removeAllViews()
        for (key in docked) {
            val app = appByKey[key]
            if (app != null) dock.addView(appTile(app, dw, dh, true, drop))
            else dock.addView(siteTile(siteByKey.getValue(key), dw, dh, true, drop))
        }
        dock.visibility = if (docked.isEmpty()) View.GONE else View.VISIBLE
        dockHint.visibility = if (docked.isEmpty()) View.VISIBLE else View.GONE

        grid.removeAllViews()
        for (chunk in apps.filter { it.key !in hidden }.chunked(columns)) {
            val row = RowLayout(this)
            for (app in chunk) row.addView(appTile(app, appW, appH, false))
            grid.addView(row, WRAP_CONTENT, WRAP_CONTENT)
        }

        siteRow.removeAllViews()
        for (site in sites) siteRow.addView(siteTile(site, appW, appH, false))
        siteRow.addView(TileView(this, appW, appH).apply {
            key = "action:add-site"
            label = "Add website"
            glyph = Glyphs.PLUS
            tint = 0xFFB8C4E0.toInt()
            setOnClickListener { editSite(null) }
        })

        val target = (if (firstRender) null else findTile(wantKey, wantDock)) ?: firstTile()
        if (apps.isNotEmpty()) firstRender = false
        if (target != null) {
            // Also the fallback the system uses when it has to pick a highlight by itself.
            if (Build.VERSION.SDK_INT >= 26) target.isFocusedByDefault = true
            target.requestFocus()
        }
    }

    private fun findTile(key: String?, preferDock: Boolean): View? {
        if (key == null) return null
        fun search(group: ViewGroup): View? {
            for (i in 0 until group.childCount) {
                val c = group.getChildAt(i)
                if (c is TileView && c.key == key) return c
                if (c is ViewGroup) search(c)?.let { return it }
            }
            return null
        }
        val inDock = search(dock)
        val elsewhere = search(grid) ?: search(siteRow)
        return if (preferDock) inDock ?: elsewhere else elsewhere ?: inDock
    }

    private fun appTile(app: AppEntry, w: Int, h: Int, inDock: Boolean, drop: Float = uf(42f)) =
        TileView(this, w, h, drop).apply {
            key = app.key
            label = app.label
            art = app.art
            tint = app.tint
            setOnClickListener { launch(app) }
            setOnLongClickListener { appMenu(app, inDock); true }
        }

    private fun siteTile(site: Site, w: Int, h: Int, inDock: Boolean, drop: Float = uf(42f)) =
        TileView(this, w, h, drop).apply {
            key = site.key
            label = site.name
            art = artFor(site)
            tint = Tint.fromText(site.host)
            setOnClickListener { openSite(site) }
            setOnLongClickListener { siteMenu(site, inDock); true }
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

    private fun openBuiltIn(url: String?, tvMode: Boolean) {
        val i = Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (url != null) i.putExtra(BrowserActivity.EXTRA_URL, url)
        i.putExtra(BrowserActivity.EXTRA_TV_MODE, tvMode)
        start(i)
    }

    /** The installed browser to hand websites to: Brave if present, else the TV's default. */
    private fun externalBrowser(): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val handlers = try {
            packageManager.queryIntentActivities(probe, 0).mapNotNull { it.activityInfo?.packageName }
        } catch (e: Exception) {
            emptyList()
        }.filter { it != packageName }.distinct()
        if (handlers.isEmpty()) return null
        val preferred = try {
            packageManager.resolveActivity(probe, 0)?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        return handlers.firstOrNull { it.startsWith("com.brave.browser") }
            ?: preferred?.takeIf { it in handlers }
            ?: handlers.first()
    }

    private fun browserName(pkg: String): String =
        apps.firstOrNull { it.pkg == pkg }?.label ?: try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }

    private fun openSite(site: Site) {
        val external = if (store.preferBuiltInBrowser) null else externalBrowser()
        if (external == null) {
            openBuiltIn(site.url, site.tvMode)
            return
        }
        start(
            Intent(Intent.ACTION_VIEW, Uri.parse(site.url)).setPackage(external).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(BrowserActivity.EXTRA_URL, site.url).putExtra(BrowserActivity.EXTRA_TV_MODE, site.tvMode),
        )
    }

    /** The globe button: just open the browser. */
    private fun openBrowserHome() {
        val external = if (store.preferBuiltInBrowser) null else externalBrowser()
        val launch = external?.let {
            packageManager.getLaunchIntentForPackage(it) ?: packageManager.getLeanbackLaunchIntentForPackage(it)
        }
        if (launch != null) start(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else openBuiltIn(null, false)
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
        netChip.glyph = when {
            info == null || !info.isConnected -> Glyphs.OFFLINE
            info.type == ConnectivityManager.TYPE_ETHERNET -> Glyphs.WIRED
            else -> Glyphs.WIFI
        }
    }

    /** Re-render and put the highlight back on [key]. */
    private fun refresh(key: String?, inDock: Boolean) {
        focusKey = key
        focusInDock = inDock
        render()
    }

    private fun moveFavorite(key: String, delta: Int) {
        val all = store.favorites.toMutableList()
        // Only items currently on screen count as neighbours.
        val visible = (0 until dock.childCount).map { (dock.getChildAt(it) as TileView).key }
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

    private fun addFavoriteRows(d: GlassDialog, key: String, inDock: Boolean) {
        if (store.isFavorite(key)) {
            if (inDock) {
                d.row("Move left") { moveFavorite(key, -1) }
                d.row("Move right") { moveFavorite(key, 1) }
                d.row("Move to front") { moveFavorite(key, Int.MIN_VALUE) }
            }
            d.row("Remove from Dock") {
                store.toggleFavorite(key)
                refresh(key, false)
            }
        } else {
            d.row("Add to Dock") {
                if (dock.childCount >= MAX_DOCK) {
                    toast("The dock is full. Remove something first.")
                } else {
                    store.toggleFavorite(key)
                    refresh(key, true)
                }
            }
        }
    }

    private fun appMenu(app: AppEntry, inDock: Boolean) {
        val d = GlassDialog(this, app.label, app.pkg)
        d.row("Open") { launch(app) }
        addFavoriteRows(d, app.key, inDock)
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

    private fun siteMenu(site: Site, inDock: Boolean) {
        val d = GlassDialog(this, site.name, site.url)
        d.row("Open") { openSite(site) }
        if (!store.preferBuiltInBrowser && externalBrowser() != null) {
            d.row("Open with the pointer browser") { openBuiltIn(site.url, site.tvMode) }
        }
        addFavoriteRows(d, site.key, inDock)
        d.row("Edit") { editSite(site) }
        d.row("Remove", danger = true) {
            store.removeSite(site)
            SiteIcons.forget(this, site)
            siteArt.remove(site.id)
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
        d.row({ if (tv) "Built-in browser: arrow keys go to the page" else "Built-in browser: on-screen pointer" }, keepOpen = true) {
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
                if (existing.url != address) SiteIcons.forget(this, existing)
                siteArt.remove(existing.id)
                existing.name = title
                existing.url = address
                existing.tvMode = tv
                store.updateSite(existing)
                existing.key
            }
            refresh(key, false)
            fetchSiteIcons()
        }
        d.show()
    }

    private fun launcherMenu() {
        val d = GlassDialog(this, "Glass Home", "Hold OK on any tile for its options.")
        d.row("TV settings") { openSystemSettings() }
        d.row({ "Backdrop: ${Wallpapers.name(store.wallpaper)}" }, keepOpen = true) {
            store.wallpaper = (store.wallpaper + 1) % Wallpapers.count
            applyWallpaper(animate = true)
        }
        val external = externalBrowser()
        if (external != null) {
            val name = browserName(external)
            d.row({ "Websites open in: " + if (store.preferBuiltInBrowser) "built-in pointer browser" else name }, keepOpen = true) {
                store.preferBuiltInBrowser = !store.preferBuiltInBrowser
            }
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

    companion object {
        const val MAX_DOCK = 8
    }
}
