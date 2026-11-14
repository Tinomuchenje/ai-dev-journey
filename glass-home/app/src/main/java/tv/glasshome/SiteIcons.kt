package tv.glasshome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gives pinned websites a proper icon: the site's own home-screen icon, fetched once
 * straight from the site and kept on disk. Nothing is asked of any third-party service.
 */
object SiteIcons {
    private fun file(ctx: Context, site: Site) = File(File(Storage.of(ctx).filesDir, "site-icons").apply { mkdirs() }, site.id + ".png")

    /** The saved icon, if one was fetched earlier. Cheap enough for the main thread. */
    fun cached(ctx: Context, site: Site): Bitmap? {
        val f = file(ctx, site)
        return if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.path) else null
    }

    fun forget(ctx: Context, site: Site) {
        file(ctx, site).delete()
    }

    /** Tries to download the icon. Blocking; call from a background thread. True if one was saved. */
    fun fetch(ctx: Context, site: Site): Boolean {
        val uri = Uri.parse(site.url)
        val host = uri.host ?: return false
        val scheme = if (uri.scheme == "http") "http" else "https"
        val f = file(ctx, site)
        if (f.exists()) return false // found before, or already known to have none
        for (path in listOf("/apple-touch-icon.png", "/apple-touch-icon-precomposed.png", "/favicon.ico")) {
            val raw = download("$scheme://$host$path") ?: continue
            // Tiny favicons look worse blown up than a clean letter does.
            if (raw.width < 57 || raw.height < 57) continue
            val icon = rounded(raw)
            FileOutputStream(f).use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return true
        }
        f.createNewFile() // empty marker: do not ask this site again
        return false
    }

    private fun download(address: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(address).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) GlassHome")
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > 2_000_000) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Web icons are plain squares; round them off the way app icons are. */
    private fun rounded(src: Bitmap): Bitmap {
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        p.shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.22f, size * 0.22f, p)
        return out
    }
}
