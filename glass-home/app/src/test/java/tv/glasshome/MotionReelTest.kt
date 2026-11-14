package tv.glasshome

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assume.assumeTrue
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
 * Records the home screen's animations frame by frame (30 per second) into
 * app/build/reel, to be stitched into a video. Skipped unless GLASS_REEL is set:
 *
 *   GLASS_REEL=1 ./gradlew testDebugUnitTest --tests '*MotionReel*'
 *   ffmpeg -framerate 30 -i app/build/reel/f%04d.png -pix_fmt yuv420p reel.mp4
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class MotionReelTest {
    private var n = 0
    private val dir = File("build/reel")
    private lateinit var home: View
    private val small = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
    private val full = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun write() {
        Canvas(small).drawBitmap(full, null, RectF(0f, 0f, 1280f, 720f), smooth)
        FileOutputStream(File(dir, "f%04d.png".format(n++))).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun frames(count: Int) = repeat(count) {
        ShadowLooper.idleMainLooper(33, TimeUnit.MILLISECONDS)
        home.draw(Canvas(full))
        write()
    }

    private fun dialogFrames(count: Int) = repeat(count) {
        ShadowLooper.idleMainLooper(33, TimeUnit.MILLISECONDS)
        val sharp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        home.draw(Canvas(sharp))
        var soft = sharp
        repeat(4) { soft = Bitmap.createScaledBitmap(soft, soft.width / 2, soft.height / 2, true) }
        repeat(3) { soft = Bitmap.createScaledBitmap(soft, soft.width * 2, soft.height * 2, true) }
        val cv = Canvas(full)
        cv.drawBitmap(soft, null, RectF(0f, 0f, 1920f, 1080f), smooth)
        cv.drawColor(0x8C000000.toInt())
        ShadowDialog.getLatestDialog().window!!.decorView.draw(cv)
        write()
    }

    private fun tiles(): List<TileView> {
        val out = ArrayList<TileView>()
        fun walk(v: View) {
            if (v is TileView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(home)
        return out
    }

    private fun focused() = home.findFocus() as TileView

    private fun press(direction: Int, hold: Int = 13) {
        (focused().focusSearch(direction) as? TileView)?.requestFocus()
        frames(hold)
    }

    @Test
    fun record() {
        assumeTrue(System.getenv("GLASS_REEL") != null)
        dir.deleteRecursively()
        dir.mkdirs()
        val ctx = RuntimeEnvironment.getApplication()
        val fixture = HomeScreenTest()
        fixture.stepFrameByFrame()
        val pm = shadowOf(ctx.packageManager)
        val infos = fixture.standIns().mapIndexed { i, (pkg, label, icon) ->
            pm.addDrawableResolution(pkg, 0x7f990000 + i, icon)
            ResolveInfo().apply {
                nonLocalizedLabel = label
                activityInfo = ActivityInfo().apply {
                    this.icon = 0x7f990000 + i
                    packageName = pkg
                    name = "$pkg.Main"
                    applicationInfo = ApplicationInfo().apply { packageName = pkg }
                }
            }
        }
        @Suppress("DEPRECATION")
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), infos)

        val a = Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        home = a.window.decorView
        // Wait (without advancing the animation clock) for the app list to arrive.
        var waited = 0
        while (tiles().size < 10 && waited++ < 200) {
            Thread.sleep(20)
            ShadowLooper.idleMainLooper()
        }

        frames(45)                       // the opening
        press(View.FOCUS_RIGHT, 16)      // along the dock; the shelf names each app
        press(View.FOCUS_RIGHT, 16)
        press(View.FOCUS_RIGHT, 16)
        press(View.FOCUS_RIGHT, 18)
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) // nowhere to go
        frames(18)
        press(View.FOCUS_DOWN, 16)       // into the grid
        press(View.FOCUS_LEFT)
        press(View.FOCUS_LEFT)
        press(View.FOCUS_DOWN, 16)
        press(View.FOCUS_DOWN, 20)       // the page scrolls
        press(View.FOCUS_UP, 16)
        press(View.FOCUS_UP, 14)
        press(View.FOCUS_UP, 20)         // back to the dock
        focused().performLongClick()     // the menu
        dialogFrames(30)
        val move = ShadowDialog.getLatestDialog().window!!.decorView.let { root ->
            val rows = ArrayList<android.widget.TextView>()
            fun walk(v: View) {
                if (v is android.widget.TextView && v.isClickable && v.text.toString() == "Move") rows += v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            rows.single()
        }
        move.performClick()              // pick the tile up and carry it
        frames(16)
        for (code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            frames(14)
        }
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        frames(24)
    }
}
