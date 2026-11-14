package tv.glasshome

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Runs the real launcher activity on the JVM against a fake set of installed apps, checks
 * the behaviour a remote user depends on, and writes PNGs of what it draws to
 * app/build/screenshots so the design can be reviewed without a TV.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class HomeScreenTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    /** A stand-in app: a name and a rough sketch of the kind of icon it would ship. */
    private class FakeApp(val pkg: String, val label: String, val bg: Int, val draw: (Canvas, Paint) -> Unit)

    private fun c(hex: Long) = hex.toInt()

    private fun letter(text: String, color: Int, size: Float = 120f): (Canvas, Paint) -> Unit = { cv, p ->
        p.color = color
        p.textSize = size
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        cv.drawText(text, 96f, 96f - (p.ascent() + p.descent()) / 2f, p)
    }

    private fun play(plate: Int, arrow: Int): (Canvas, Paint) -> Unit = { cv, p ->
        p.color = plate
        cv.drawRoundRect(RectF(30f, 54f, 162f, 138f), 26f, 26f, p)
        p.color = arrow
        cv.drawPath(Path().apply { moveTo(80f, 74f); lineTo(122f, 96f); lineTo(80f, 118f); close() }, p)
    }

    private val installed = listOf(
        FakeApp("com.netflix.mediaclient", "Netflix", c(0xFF0B0B0B), letter("N", c(0xFFE50914), 150f)),
        FakeApp("com.google.android.youtube", "YouTube", c(0xFFFFFFFF), play(c(0xFFFF0033), Color.WHITE)),
        FakeApp("com.amazon.avod.thirdpartyclient", "Prime Video", c(0xFF0F79AF)) { cv, p ->
            p.color = Color.WHITE
            p.style = Paint.Style.STROKE
            p.strokeWidth = 14f
            p.strokeCap = Paint.Cap.ROUND
            cv.drawArc(RectF(40f, 30f, 152f, 130f), 30f, 120f, false, p)
            p.style = Paint.Style.FILL
        },
        FakeApp("org.videolan.vlc", "VLC", c(0xFFF6F6F6)) { cv, p ->
            p.color = c(0xFFFF8800)
            cv.drawPath(Path().apply { moveTo(96f, 30f); lineTo(146f, 150f); lineTo(46f, 150f); close() }, p)
            p.color = Color.WHITE
            cv.drawRect(60f, 96f, 132f, 112f, p)
        },
        FakeApp("com.spotify.music", "Spotify", c(0xFF121212)) { cv, p ->
            p.color = c(0xFF1ED760)
            cv.drawCircle(96f, 96f, 62f, p)
            p.color = c(0xFF121212)
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            for ((i, y) in listOf(70f, 94f, 116f).withIndex()) {
                p.strokeWidth = 12f - i * 2
                cv.drawArc(RectF(52f + i * 6, y, 140f - i * 6, y + 44f), 205f, 130f, false, p)
            }
            p.style = Paint.Style.FILL
        },
        FakeApp("com.android.settings", "Settings", c(0xFF8E96A8)) { cv, p ->
            p.color = Color.WHITE
            p.style = Paint.Style.STROKE
            p.strokeWidth = 20f
            cv.drawCircle(96f, 96f, 40f, p)
            p.style = Paint.Style.FILL
        },
        FakeApp("com.vendor.filemanager", "File Manager", c(0xFFFFC53D)) { cv, p ->
            p.color = Color.WHITE
            cv.drawRoundRect(RectF(40f, 62f, 152f, 140f), 12f, 12f, p)
            cv.drawRoundRect(RectF(40f, 48f, 100f, 80f), 10f, 10f, p)
        },
        FakeApp("com.vendor.appstore", "App Store", c(0xFFF0456F), letter("A", Color.WHITE)),
        FakeApp("com.vendor.media", "Media Player", c(0xFF6D5BF0), play(c(0x00000000), Color.WHITE)),
        FakeApp("com.facebook.katana", "Facebook", c(0xFF1877F2), letter("f", Color.WHITE, 150f)),
        FakeApp("com.twitter.android", "Twitter", c(0xFF000000), letter("X", Color.WHITE)),
        FakeApp("com.zhiliaoapp.musically", "TikTok", c(0xFF010101), letter("♪", c(0xFF25F4EE))),
        FakeApp("com.vendor.cleaner", "TV Cleaner With A Very Long Name", c(0xFF19B394), letter("✦", Color.WHITE)),
        FakeApp("com.vendor.browser", "Browser", c(0xFFFDFDFD)) { cv, p ->
            p.color = c(0xFF2F6BFF)
            cv.drawCircle(96f, 96f, 58f, p)
            p.color = Color.WHITE
            cv.drawPath(Path().apply { moveTo(96f, 52f); lineTo(116f, 96f); lineTo(96f, 140f); lineTo(76f, 96f); close() }, p)
        },
    )

    private fun iconFor(app: FakeApp): Drawable {
        val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = app.bg
        cv.drawRoundRect(RectF(6f, 6f, 186f, 186f), 44f, 44f, p)
        app.draw(cv, Paint(Paint.ANTI_ALIAS_FLAG))
        return BitmapDrawable(ctx.resources, bmp)
    }

    /**
     * By default the JVM test environment fast-forwards every animation to its end the
     * moment it starts. This makes it step frame by frame instead (16 ms apart), so a test
     * can look at an animation while it is still under way.
     */
    internal fun stepFrameByFrame() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    /** The stand-in apps as (package, name, icon), for other tests that want the same screen. */
    internal fun standIns(): List<Triple<String, String, Drawable>> = installed.map { Triple(it.pkg, it.label, iconFor(it)) }

    private fun resolveInfo(pkg: String, label: String, iconId: Int = 0) = ResolveInfo().apply {
        nonLocalizedLabel = label
        activityInfo = ActivityInfo().apply {
            icon = iconId
            packageName = pkg
            name = "$pkg.Main"
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
    }

    @Before
    fun installFakeApps() {
        val pm = shadowOf(ctx.packageManager)
        val infos = installed.mapIndexed { index, app ->
            val iconId = 0x7f990000 + index
            pm.addDrawableResolution(app.pkg, iconId, iconFor(app))
            resolveInfo(app.pkg, app.label, iconId)
        }
        @Suppress("DEPRECATION")
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), infos)
    }

    private var screen: View? = null
    private val scratch = Canvas(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

    private fun settle() {
        // App loading hops through a background thread; animations and smooth scrolling
        // advance only as frames are drawn, so draw a second's worth of them.
        repeat(12) {
            Thread.sleep(20)
            ShadowLooper.idleMainLooper()
        }
        repeat(110) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            screen?.draw(scratch)
        }
        // A bounded wait: running every pending task would also fire the idle screensaver timer.
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS)
        screen?.draw(scratch)
    }

    private fun launch(): HomeActivity {
        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        screen = activity.window.decorView
        settle()
        return activity
    }

    private fun views(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private inline fun <reified T : View> all(root: View): List<T> = views(root).filterIsInstance<T>()

    private fun tiles(root: View) = all<TileView>(root)

    private fun focus(a: Activity) = a.window.decorView.findFocus() as TileView

    private fun content(a: Activity): View = a.findViewById(android.R.id.content)

    private fun save(bmp: Bitmap, name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shootHome(a: HomeActivity, name: String) {
        val view = a.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        save(bmp, name)
    }

    /**
     * The dialog composited over the home screen the way it appears on the TV: dimmed and,
     * on Android 12+, blurred. The blur is done by the GPU on a real device, which this
     * JVM renderer cannot do, so it is approximated here by repeatedly halving and doubling the image.
     */
    private fun shootDialog(a: HomeActivity, name: String) {
        val home = a.window.decorView
        val sharp = Bitmap.createBitmap(home.width, home.height, Bitmap.Config.ARGB_8888)
        home.draw(Canvas(sharp))
        var soft = sharp
        repeat(4) { soft = Bitmap.createScaledBitmap(soft, soft.width / 2, soft.height / 2, true) }
        repeat(3) { soft = Bitmap.createScaledBitmap(soft, soft.width * 2, soft.height * 2, true) }
        val bmp = Bitmap.createBitmap(home.width, home.height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawBitmap(soft, null, RectF(0f, 0f, home.width.toFloat(), home.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        cv.drawColor(0x8C000000.toInt())
        ShadowDialog.getLatestDialog().window!!.decorView.draw(cv)
        save(bmp, name)
    }

    private fun dialogRows(d: Dialog) = all<TextView>(d.window!!.decorView).filter { it.isClickable && it !is EditText }

    private fun pressRow(text: String) {
        val d = ShadowDialog.getLatestDialog()
        val row = dialogRows(d).firstOrNull { it.text.toString().startsWith(text) }
        assertNotNull("menu row '$text' not found in ${dialogRows(d).map { it.text }}", row)
        row!!.performClick()
        settle()
    }

    private fun holdOk(tile: TileView) {
        tile.performLongClick()
        settle()
    }

    @Test
    fun firstRunFillsTheDockWithStreamingAppsAndFocusesTheFirstOne() {
        val a = launch()
        val store = Store(a)
        assertEquals(
            listOf("Netflix", "YouTube", "Prime Video", "Spotify", "VLC"),
            store.favorites.map { key -> tiles(content(a)).first { it.key == key }.label }
        )
        assertEquals(listOf("YouTube TV", "Google"), store.sites.map { it.name })
        assertEquals("Netflix", focus(a).label)
        assertTrue("first tile sits in the dock", focus(a).parent is DockLayout)
        shootHome(a, "home")
    }

    @Test
    fun theOpeningAnimationLeavesEverythingExactlyInPlace() {
        val a = launch()
        for (v in views(content(a))) {
            if (v !is TileView && v !is ChipView && v !is DockLayout) continue
            assertEquals("alpha of $v", 1f, v.alpha, 0.001f)
            assertEquals("offset of $v", 0f, v.translationY, 0.001f)
            assertEquals("scale of $v", 1f, v.scaleX, 0.001f)
        }
    }

    @Test
    fun theHighlightGlidesToTheFocusedTileAndSettlesOnIt() {
        val a = launch()
        val lens = all<FocusLens>(content(a)).single()
        fun expected(tile: TileView): RectF {
            val card = RectF()
            tile.cardRect(card)
            val at = IntArray(2)
            val base = IntArray(2)
            tile.getLocationOnScreen(at)
            lens.getLocationOnScreen(base)
            card.offset((at[0] - base[0]).toFloat(), (at[1] - base[1]).toFloat())
            return card
        }
        fun assertOn(tile: TileView) {
            val want = expected(tile)
            val got = lens.drawn
            assertEquals("left", want.left, got.left, 1f)
            assertEquals("top", want.top, got.top, 1f)
            assertEquals("right", want.right, got.right, 1f)
            assertEquals("bottom", want.bottom, got.bottom, 1f)
        }
        assertOn(focus(a))

        // Along the dock, down into the grid, and far enough to make the page scroll.
        (focus(a).focusSearch(View.FOCUS_RIGHT) as TileView).requestFocus()
        settle()
        assertEquals("YouTube", focus(a).label)
        assertOn(focus(a))
        val deep = tiles(content(a)).first { it.label == "Twitter" }
        deep.requestFocus()
        settle()
        assertOn(deep)

        val first = tiles(content(a)).first()
        first.requestFocus()
        settle()
        assertOn(first)

        // From here, watch the motion itself rather than only where it ends up.
        stepFrameByFrame()
        val next = first.focusSearch(View.FOCUS_RIGHT) as TileView
        next.requestFocus()
        repeat(5) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            screen!!.draw(scratch)
        }
        val mid = RectF(lens.drawn)
        val start = expected(first)
        val end = expected(next)
        assertTrue("mid-glide the ring has left the first tile", mid.right > start.right + 1f)
        assertTrue("and its trailing edge has not arrived yet", mid.left < end.left - 1f)
        assertTrue("so it is stretched wider than a tile", mid.width() > end.width() + 4f)
        settle()
        assertOn(next)

        // A press with nowhere to go shoves the tile and lets it spring back to rest.
        first.requestFocus()
        settle()
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        var furthest = 0f
        repeat(30) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            furthest = minOf(furthest, first.translationX)
        }
        assertTrue("tile is shoved left (reached $furthest)", furthest < -4f)
        settle()
        assertEquals(0f, first.translationX, 0.001f)
        assertOn(first)
    }

    @Test
    fun reducedMotionTurnsTheAnimationsOff() {
        val a = launch()
        all<ChipView>(content(a)).first { it.contentDescription == "Settings" }.performClick()
        settle()
        pressRow("Motion: full")
        assertTrue(Store(a).reducedMotion && Motion.reduced)
        ShadowDialog.getLatestDialog().dismiss()
        val next = focus(a).focusSearch(View.FOCUS_RIGHT) as TileView
        next.requestFocus()
        // No waiting: with motion off, the highlight is simply there.
        screen!!.draw(scratch)
        val lens = all<FocusLens>(content(a)).single()
        val card = RectF()
        next.cardRect(card)
        assertEquals(card.width(), lens.drawn.width(), 1f)
        Motion.reduced = false
    }

    @Test
    fun everyInstalledAppIsListedExceptTheLauncherItself() {
        val a = launch()
        val labels = tiles(content(a)).map { it.label }
        for (app in installed) assertTrue("${app.label} missing", app.label in labels)
        assertFalse("Glass Home" in labels)
    }

    @Test
    fun hidingAnAppRemovesItEverywhereAndItCanBeRestored() {
        val a = launch()
        val facebook = tiles(content(a)).first { it.label == "Facebook" }
        facebook.requestFocus()
        settle()
        holdOk(facebook)
        shootDialog(a, "menu")
        pressRow("Hide from home")
        assertFalse(tiles(content(a)).any { it.label == "Facebook" })
        assertEquals(1, Store(a).hidden.size)

        // Settings button -> Hidden apps -> Facebook brings it back.
        all<ChipView>(content(a)).first { it.contentDescription == "Settings" }.performClick()
        settle()
        pressRow("Hidden apps")
        pressRow("Facebook")
        assertTrue(tiles(content(a)).any { it.label == "Facebook" })
        assertTrue(Store(a).hidden.isEmpty())
    }

    @Test
    fun menuKeyOpensTheOptionsOfTheFocusedTile() {
        val a = launch()
        a.onKeyUp(KeyEvent.KEYCODE_MENU, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
        settle()
        assertTrue(dialogRows(ShadowDialog.getLatestDialog()).any { it.text.toString() == "Remove from Dock" })
    }

    private fun key(a: HomeActivity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        settle()
    }

    @Test
    fun dockItemsCanBeAddedCarriedToANewPlaceAndRemoved() {
        val a = launch()
        val store = Store(a)
        val before = store.favorites

        holdOk(tiles(content(a)).first { it.label == "Settings" })
        pressRow("Add to Dock")
        val settingsKey = store.favorites.last()
        assertEquals(before + settingsKey, store.favorites)
        assertTrue("highlight follows it into the dock", focus(a).key == settingsKey && focus(a).parent is DockLayout)

        // Pick it up and carry it with the arrows.
        holdOk(focus(a))
        pressRow("Move")
        assertTrue("the carried tile is marked", focus(a).carried)
        assertEquals("Left or Right to move, OK to place", all<ShelfTitle>(content(a)).single().text)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        key(a, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(before.take(3) + settingsKey + before.drop(3), store.favorites)
        assertEquals("highlight stays on the carried tile", settingsKey, focus(a).key)
        repeat(6) { key(a, KeyEvent.KEYCODE_DPAD_LEFT) }
        assertEquals("it stops at the front", listOf(settingsKey) + before, store.favorites)
        key(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf(before[0], settingsKey) + before.drop(1), store.favorites)

        // OK puts it down without opening the app.
        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertFalse(focus(a).carried)
        assertEquals(null, shadowOf(a).nextStartedActivity)
        assertEquals("Settings", all<ShelfTitle>(content(a)).single().text)
        assertTrue("the other tiles are back to full strength", tiles(content(a)).all { it.alpha == 1f })

        holdOk(focus(a))
        pressRow("Remove from Dock")
        assertEquals(before, store.favorites)
    }

    @Test
    fun theShelfNamesWhateverIsFocusedInTheDock() {
        val a = launch()
        val shelf = all<ShelfTitle>(content(a)).single()
        assertEquals("Netflix", shelf.text)
        (focus(a).focusSearch(View.FOCUS_RIGHT) as TileView).requestFocus()
        settle()
        assertEquals("YouTube", shelf.text)
        // In the grid each tile carries its own caption, so the shelf goes quiet.
        (focus(a).focusSearch(View.FOCUS_DOWN) as TileView).requestFocus()
        settle()
        assertEquals(null, shelf.text)
    }

    @Test
    fun itAnswersTheHomeButtonOfCvteFirmwareAndMayStartAtPowerOn() {
        // That firmware sends Home as MAIN + HOME + COMMON_HOME; a plain HOME must still match too.
        val pm = ctx.packageManager
        for (extra in listOf(null, "android.intent.category.COMMON_HOME")) {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.packageName)
            if (extra != null) home.addCategory(extra)
            val found = pm.queryIntentActivities(home, 0).map { it.activityInfo.name }
            assertEquals("HOME + $extra", listOf(HomeActivity::class.java.name), found)
        }
        val info = pm.getActivityInfo(android.content.ComponentName(ctx, HomeActivity::class.java), 0)
        assertTrue("allowed to start before the user's storage is unlocked", info.directBootAware)
    }

    @Test
    fun beforeTheTvHasUnlockedItDoesNotJudgeWhatIsInstalled() {
        val users = shadowOf(ctx.getSystemService(Context.USER_SERVICE) as android.os.UserManager)
        users.setUserUnlocked(false)
        val a = launch()
        assertFalse("no dock is chosen from a half-visible app list", Store(a).seeded)
        users.setUserUnlocked(true)
        ctx.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        settle()
        assertTrue(Store(a).seeded)
        assertEquals("Netflix", focus(a).label)
    }

    @Test
    fun theInputsButtonAppearsOnlyWhenTheTvHasASourcePicker() {
        val plain = launch()
        assertFalse(all<ChipView>(content(plain)).first { it.contentDescription == "Inputs" }.isShown)

        // Now a TV with CVTE's source picker and live-TV screen.
        val picker = android.content.ComponentName("com.cvte.tv.globalui", "com.cvte.tv.globalui.InputSourceActivity")
        val live = android.content.ComponentName("com.cvte.tv.setting", "com.cvte.tv.setting.TvSettingActivity")
        val pm = shadowOf(ctx.packageManager)
        for (c in listOf(picker, live)) {
            @Suppress("DEPRECATION")
            pm.addResolveInfoForIntent(Intent().setComponent(c), resolveInfo(c.packageName, c.className))
        }
        val a = launch()
        val chip = all<ChipView>(content(a)).first { it.contentDescription == "Inputs" }
        assertTrue(chip.isShown)
        chip.performClick()
        settle()
        pressRow("HDMI and other inputs")
        assertEquals(picker, shadowOf(a).nextStartedActivity.component)
    }

    @Test
    fun theClockScreensaverComesOnWhenIdleAndTheFirstPressOnlyWakesIt() {
        val a = launch()
        val saver = all<ClockSaver>(a.window.decorView).single()
        assertFalse(saver.showing)
        ShadowLooper.idleMainLooper(5, TimeUnit.MINUTES)
        assertTrue("on after five idle minutes", saver.showing)
        shootHome(a, "screensaver")

        key(a, KeyEvent.KEYCODE_DPAD_CENTER)
        assertFalse(saver.showing)
        assertEquals("the waking press did not open the focused app", null, shadowOf(a).nextStartedActivity)

        // Switched off in Settings, it stays off.
        all<ChipView>(content(a)).first { it.contentDescription == "Settings" }.performClick()
        settle()
        pressRow("Clock screensaver: after 5")
        pressRow("Clock screensaver: after 15")
        assertEquals(0, Store(a).saverMinutes)
        ShadowDialog.getLatestDialog().dismiss()
        ShadowLooper.idleMainLooper(30, TimeUnit.MINUTES)
        assertFalse(saver.showing)
    }

    @Test
    fun dockTilesShrinkToFitInsteadOfRunningOffTheScreen() {
        val a = launch()
        for (name in listOf("Settings", "Facebook", "Browser")) {
            holdOk(tiles(content(a)).first { it.label == name && it.parent !is DockLayout })
            pressRow("Add to Dock")
        }
        val dock = all<DockLayout>(content(a)).single()
        assertEquals(8, dock.childCount)
        val loc = IntArray(2)
        dock.getLocationOnScreen(loc)
        assertTrue("dock fits the screen", loc[0] >= 0 && loc[0] + dock.width <= Dim.w)
        shootHome(a, "dock-full")

        // A ninth item is refused rather than squeezed in.
        holdOk(tiles(content(a)).first { it.label == "TikTok" })
        pressRow("Add to Dock")
        assertEquals(8, Store(a).favorites.size)
    }

    @Test
    fun leftAndRightStayInsideARow() {
        val a = launch()
        val first = focus(a)
        assertEquals("left at the start of the dock stays put", first, first.focusSearch(View.FOCUS_LEFT))
        assertEquals("YouTube", (first.focusSearch(View.FOCUS_RIGHT) as TileView).label)
        var last: View = first
        repeat(10) { last = last.focusSearch(View.FOCUS_RIGHT) }
        assertEquals("right at the end of the dock stays put", "VLC", (last as TileView).label)
        assertEquals("down from the dock lands on the app grid", "App Store", (first.focusSearch(View.FOCUS_DOWN) as TileView).label)
    }

    @Test
    fun websitesCanBeAddedFromWhateverIsTyped() {
        assertEquals("https://bbc.com", Store.normalizeUrl(" bbc.com "))
        assertEquals("http://192.168.1.1/admin", Store.normalizeUrl("http://192.168.1.1/admin"))
        assertEquals("https://www.google.com/search?q=weather%20harare", Store.normalizeUrl("weather harare"))

        val a = launch()
        tiles(content(a)).first { it.label == "Add website" }.performClick()
        settle()
        val fields = all<EditText>(ShadowDialog.getLatestDialog().window!!.decorView)
        fields[0].setText("bbc.com/news")
        shootDialog(a, "add-website")
        pressRow("Save")
        val site = Store(a).sites.last()
        assertEquals("Bbc", site.name)
        assertEquals("https://bbc.com/news", site.url)
        assertEquals("highlight lands on the new tile", site.key, focus(a).key)
    }

    @Test
    fun websitesOpenInBraveWhenItIsInstalledAndInTheBuiltInBrowserOtherwise() {
        val a = launch()
        val youtube = tiles(content(a)).first { it.label == "YouTube TV" }

        youtube.performClick()
        val builtIn = shadowOf(a).nextStartedActivity
        assertEquals(BrowserActivity::class.java.name, builtIn.component!!.className)
        assertEquals("https://www.youtube.com/tv", builtIn.getStringExtra(BrowserActivity.EXTRA_URL))

        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        shadowOf(ctx.packageManager).addResolveInfoForIntent(
            probe, listOf(resolveInfo("org.other.browser", "Other"), resolveInfo("com.brave.browser", "Brave"))
        )
        youtube.performClick()
        val external = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, external.action)
        assertEquals("com.brave.browser", external.`package`)
        assertEquals("https://www.youtube.com/tv", external.dataString)

        // The choice can be switched back in Settings.
        all<ChipView>(content(a)).first { it.contentDescription == "Settings" }.performClick()
        settle()
        pressRow("Websites open in:")
        ShadowDialog.getLatestDialog().dismiss()
        youtube.performClick()
        assertEquals(BrowserActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
    }

    @Test
    fun scrollingFollowsTheHighlightDownThePage() {
        val a = launch()
        val middle = tiles(content(a)).first { it.label == "Spotify" && it.parent !is DockLayout }
        middle.requestFocus()
        settle()
        val loc = IntArray(2)
        middle.getLocationOnScreen(loc)
        assertTrue("focused tile is on screen (y=${loc[1]})", loc[1] >= 0 && loc[1] + middle.height <= Dim.h)
        shootHome(a, "apps")

        val last = tiles(content(a)).last()
        last.requestFocus()
        settle()
        last.getLocationOnScreen(loc)
        assertTrue("last tile is on screen (y=${loc[1]})", loc[1] >= 0 && loc[1] + last.height <= Dim.h)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
    fun layoutIsIdenticalOnALowDensityPanel() {
        val a = launch()
        assertEquals(1f, Dim.unit, 0.001f)
        assertEquals("dock tile = 310 artwork + 2 x 18 padding", 346, focus(a).width)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
    fun layoutScalesDownTo720p() {
        val a = launch()
        assertEquals(2f / 3f, Dim.unit, 0.001f)
        val loc = IntArray(2)
        for (t in tiles(content(a))) {
            t.getLocationOnScreen(loc)
            assertTrue("${t.label} fits horizontally", loc[0] >= 0 && loc[0] + t.width <= 1280)
        }
        shootHome(a, "home-720p")
    }

    @Test
    @Config(sdk = [23], qualifiers = "w960dp-h540dp-land-xhdpi")
    fun worksOnOldAndroidToo() {
        val a = launch()
        assertEquals("Netflix", focus(a).label)
    }
}
