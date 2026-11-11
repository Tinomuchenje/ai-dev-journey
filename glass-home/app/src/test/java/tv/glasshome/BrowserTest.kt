package tv.glasshome

import android.content.Intent
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * The page engine itself cannot run on the JVM, so this covers the remote-control logic
 * around it: what gets loaded, how keys are interpreted, and which mode identifies as what.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class BrowserTest {

    private fun open(url: String?, tv: Boolean = false): BrowserActivity {
        val i = Intent(RuntimeEnvironment.getApplication(), BrowserActivity::class.java)
        if (url != null) i.putExtra(BrowserActivity.EXTRA_URL, url)
        i.putExtra(BrowserActivity.EXTRA_TV_MODE, tv)
        val a = Robolectric.buildActivity(BrowserActivity::class.java, i).setup().get()
        ShadowLooper.idleMainLooper()
        return a
    }

    private fun press(a: BrowserActivity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun opensThePinnedSiteAsADesktopBrowser() {
        val a = open("https://example.org/page")
        assertEquals("https://example.org/page", shadowOf(a.web).lastLoadedUrl)
        val ua = a.web.settings.userAgentString
        assertTrue(ua, ua.contains("X11; Linux x86_64"))
        assertFalse(ua, ua.contains("Mobile"))
        assertTrue(a.web.settings.javaScriptEnabled)
    }

    @Test
    fun withNoAddressItShowsTheBuiltInStartPage() {
        val a = open(null)
        val data = shadowOf(a.web).lastLoadDataWithBaseURL
        assertTrue(data.data.contains("Where to?"))
    }

    @Test
    fun arrowKeysMoveThePointerAndStopAtTheScreenEdge() {
        val a = open("https://example.org")
        val startX = a.px
        val startY = a.py
        assertEquals(Dim.w / 2f, startX, 0.5f)
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("moved right", a.px > startX)
        assertEquals("no vertical drift", startY, a.py, 0.01f)
        press(a, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("moved up", a.py < startY)
        repeat(400) { press(a, KeyEvent.KEYCODE_DPAD_LEFT) }
        assertEquals("clamped at the left edge", 0f, a.px, 0.01f)
    }

    @Test
    fun tvModeIdentifiesAsATelevisionAndLeavesArrowKeysToThePage() {
        val a = open("https://www.youtube.com/tv", tv = true)
        assertTrue(a.tvMode)
        assertTrue(a.web.settings.userAgentString.contains("SmartTV"))
        val x = a.px
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("pointer is not driven in direct mode", x, a.px, 0.01f)
    }

    @Test
    fun backLeavesWhenThereIsNoHistoryAndMenuOpensTheMenu() {
        val a = open("https://example.org")
        press(a, KeyEvent.KEYCODE_MENU)
        val menu = ShadowDialog.getLatestDialog()
        assertTrue(menu.isShowing)
        menu.dismiss()
        assertFalse(a.isFinishing)
        press(a, KeyEvent.KEYCODE_BACK)
        assertTrue(a.isFinishing)
    }

    @Test
    fun keysFromTheOnScreenKeyboardAreNotTreatedAsRemotePresses() {
        val a = open("https://example.org")
        val x = a.px
        val soft = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0, 0, -1, 0, KeyEvent.FLAG_SOFT_KEYBOARD)
        a.dispatchKeyEvent(soft)
        assertEquals(x, a.px, 0.01f)
    }
}
