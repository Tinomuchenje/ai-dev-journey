package tv.glasshome

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
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

    private class FakeApp(val pkg: String, val label: String, val color: Int)

    private val installed = listOf(
        FakeApp("com.netflix.mediaclient", "Netflix", 0xFFE50914.toInt()),
        FakeApp("com.google.android.youtube", "YouTube", 0xFFFF0000.toInt()),
        FakeApp("com.amazon.avod.thirdpartyclient", "Prime Video", 0xFF00A8E1.toInt()),
        FakeApp("org.videolan.vlc", "VLC", 0xFFFF8800.toInt()),
        FakeApp("com.spotify.music", "Spotify", 0xFF1DB954.toInt()),
        FakeApp("com.android.settings", "Settings", 0xFF8A93A6.toInt()),
        FakeApp("com.vendor.filemanager", "File Manager", 0xFFF2B01E.toInt()),
        FakeApp("com.vendor.appstore", "App Store", 0xFFE83E6C.toInt()),
        FakeApp("com.vendor.media", "Media Player", 0xFF7C6CF2.toInt()),
        FakeApp("com.facebook.katana", "Facebook", 0xFF1877F2.toInt()),
        FakeApp("com.twitter.android", "Twitter", 0xFF1DA1F2.toInt()),
        FakeApp("com.zhiliaoapp.musically", "TikTok", 0xFF111111.toInt()),
        FakeApp("com.vendor.cleaner", "TV Cleaner With A Very Long Name", 0xFF2FB6A0.toInt()),
        FakeApp("com.vendor.browser", "Browser", 0xFFFFFFFF.toInt()),
    )

    private fun iconFor(app: FakeApp): Drawable {
        val bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = app.color
        c.drawRoundRect(RectF(8f, 8f, 184f, 184f), 44f, 44f, p)
        p.color = if (app.color == Color.WHITE) 0xFF3366FF.toInt() else Color.WHITE
        p.textSize = 110f
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        c.drawText(app.label.take(1), 96f, 134f, p)
        return BitmapDrawable(ctx.resources, bmp)
    }

    @Before
    fun installFakeApps() {
        val pm = shadowOf(ctx.packageManager)
        val infos = installed.mapIndexed { index, app ->
            val iconId = 0x7f990000 + index
            pm.addDrawableResolution(app.pkg, iconId, iconFor(app))
            ResolveInfo().apply {
                nonLocalizedLabel = app.label
                activityInfo = ActivityInfo().apply {
                    icon = iconId
                    packageName = app.pkg
                    name = app.pkg + ".Main"
                    applicationInfo = ApplicationInfo().apply { packageName = app.pkg }
                }
            }
        }
        @Suppress("DEPRECATION")
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), infos)
    }

    private var screen: View? = null
    private val scratch = Canvas(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

    private fun settle() {
        // App loading hops through a background thread; animations and smooth scrolling
        // advance only as frames are drawn, so draw a second's worth of them.
        repeat(10) {
            Thread.sleep(15)
            ShadowLooper.idleMainLooper()
        }
        repeat(60) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            screen?.draw(scratch)
        }
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        screen?.draw(scratch)
    }

    private fun launch(): HomeActivity {
        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        screen = activity.window.decorView
        settle()
        return activity
    }

    private fun tiles(root: View): List<TileView> {
        val out = ArrayList<TileView>()
        fun walk(v: View) {
            if (v is TileView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun focus(a: Activity) = a.window.decorView.findFocus() as TileView

    private fun content(a: Activity): View = a.findViewById(android.R.id.content)

    private fun shoot(view: View, name: String, background: Drawable? = null) {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        background?.let {
            it.setBounds(0, 0, view.width, view.height)
            it.draw(c)
        }
        view.draw(c)
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shootHome(a: HomeActivity, name: String) {
        shoot(a.window.decorView, name, BitmapDrawable(a.resources, Wallpapers.make(Store(a).wallpaper)))
    }

    /** The dialog composited over the dimmed home screen, the way it appears on the TV. */
    private fun shootDialog(a: HomeActivity, name: String) {
        val home = a.window.decorView
        val bmp = Bitmap.createBitmap(home.width, home.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        BitmapDrawable(a.resources, Wallpapers.make(Store(a).wallpaper)).apply {
            setBounds(0, 0, home.width, home.height)
            draw(c)
        }
        home.draw(c)
        c.drawColor(0x8C000000.toInt())
        ShadowDialog.getLatestDialog().window!!.decorView.draw(c)
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun dialogRows(d: Dialog): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.isClickable) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(d.window!!.decorView)
        return out
    }

    private fun pressRow(text: String) {
        val d = ShadowDialog.getLatestDialog()
        val row = dialogRows(d).firstOrNull { it.text.toString().startsWith(text) }
        assertNotNull("menu row '$text' not found in ${dialogRows(d).map { it.text }}", row)
        row!!.performClick()
        settle()
    }

    @Test
    fun firstRunPinsStreamingAppsAndFocusesTheFirstOne() {
        val a = launch()
        val store = Store(a)
        assertEquals(
            listOf("Netflix", "YouTube", "Prime Video", "Spotify", "VLC"),
            store.favorites.map { key -> tiles(content(a)).first { it.key == key }.label }
        )
        assertEquals(listOf("YouTube TV", "Google"), store.sites.map { it.name })
        val focused = focus(a)
        assertEquals("Netflix", focused.label)
        shootHome(a, "home")
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
        tiles(content(a)).first { it.label == "Facebook" }.performLongClick()
        settle()
        shootDialog(a, "menu")
        pressRow("Hide from home")
        assertFalse(tiles(content(a)).any { it.label == "Facebook" })
        assertEquals(1, Store(a).hidden.size)

        // Settings pill -> Hidden apps -> Facebook brings it back.
        a.onKeyUp(android.view.KeyEvent.KEYCODE_MENU, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MENU))
        settle()
        ShadowDialog.getLatestDialog().dismiss()
        val settingsPill = pills(content(a)).first { it.text == "Settings" }
        settingsPill.performClick()
        settle()
        pressRow("Hidden apps")
        pressRow("Facebook")
        assertTrue(tiles(content(a)).any { it.label == "Facebook" })
        assertTrue(Store(a).hidden.isEmpty())
    }

    private fun pills(root: View): List<PillView> {
        val out = ArrayList<PillView>()
        fun walk(v: View) {
            if (v is PillView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun favoritesCanBeAddedReorderedAndRemoved() {
        val a = launch()
        val store = Store(a)
        val before = store.favorites

        tiles(content(a)).first { it.label == "Settings" }.performLongClick()
        settle()
        pressRow("Add to Favorites")
        val settingsKey = store.favorites.last()
        assertEquals(before + settingsKey, store.favorites)

        // Its copy in the Favorites strip can be moved to the front...
        tiles(content(a)).first { it.key == settingsKey }.performLongClick()
        settle()
        pressRow("Move to front")
        assertEquals(listOf(settingsKey) + before, store.favorites)
        assertEquals("highlight follows the moved tile", settingsKey, focus(a).key)

        // ...one step right...
        focus(a).performLongClick()
        settle()
        pressRow("Move right")
        assertEquals(listOf(before[0], settingsKey) + before.drop(1), store.favorites)

        // ...and unpinned again.
        focus(a).performLongClick()
        settle()
        pressRow("Remove from Favorites")
        assertEquals(before, store.favorites)
    }

    @Test
    fun leftAndRightStayInsideARow() {
        val a = launch()
        val firstFav = focus(a)
        assertEquals("left at the start of a strip stays put", firstFav, firstFav.focusSearch(View.FOCUS_LEFT))
        val next = firstFav.focusSearch(View.FOCUS_RIGHT) as TileView
        assertEquals("YouTube", next.label)
        var last: View = firstFav
        repeat(10) { last = last.focusSearch(View.FOCUS_RIGHT) }
        assertEquals("right at the end of a strip stays put", "VLC", (last as TileView).label)
        val below = firstFav.focusSearch(View.FOCUS_DOWN) as TileView
        assertEquals("down from Favorites lands on the Websites strip", "YouTube TV", below.label)
    }

    @Test
    fun websitesCanBeAddedFromWhateverIsTyped() {
        assertEquals("https://bbc.com", Store.normalizeUrl(" bbc.com "))
        assertEquals("http://192.168.1.1/admin", Store.normalizeUrl("http://192.168.1.1/admin"))
        assertEquals("https://www.google.com/search?q=weather%20harare", Store.normalizeUrl("weather harare"))

        val a = launch()
        tiles(content(a)).first { it.label == "Add website" }.performClick()
        settle()
        val d = ShadowDialog.getLatestDialog()
        val fields = ArrayList<android.widget.EditText>()
        fun walk(v: View) {
            if (v is android.widget.EditText) fields += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(d.window!!.decorView)
        fields[0].setText("bbc.com/news")
        shootDialog(a, "add-website")
        pressRow("Save")
        val site = Store(a).sites.last()
        assertEquals("Bbc", site.name)
        assertEquals("https://bbc.com/news", site.url)
        assertEquals("highlight lands on the new tile", site.key, focus(a).key)
    }

    @Test
    fun scrollingFollowsTheHighlightDownTheGrid() {
        val a = launch()
        val all = tiles(content(a))
        val lastApp = all.last()
        lastApp.requestFocus()
        settle()
        val loc = IntArray(2)
        lastApp.getLocationOnScreen(loc)
        assertTrue("focused tile is on screen (y=${loc[1]})", loc[1] >= 0 && loc[1] + lastApp.height <= Dim.h)
        shootHome(a, "home-scrolled")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
    fun layoutIsIdenticalOnALowDensityPanel() {
        val a = launch()
        assertEquals(1f, Dim.unit, 0.001f)
        val t = tiles(content(a)).first { it.label == "Netflix" }
        assertEquals("favourite card + padding", 352, t.width)
        shootHome(a, "home-mdpi")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
    fun layoutScalesDownTo720p() {
        val a = launch()
        assertEquals(2f / 3f, Dim.unit, 0.001f)
        val row = tiles(content(a)).filter { it.label in installed.map { i -> i.label } }.takeLast(2)
        assertTrue(row.all { it.right <= 1280 })
        shootHome(a, "home-720p")
    }

    @Test
    @Config(sdk = [23], qualifiers = "w960dp-h540dp-land-xhdpi")
    fun worksOnOldAndroidToo() {
        val a = launch()
        assertEquals("Netflix", focus(a).label)
    }
}
