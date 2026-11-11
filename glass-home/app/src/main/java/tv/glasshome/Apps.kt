package tv.glasshome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

class AppEntry(
    val component: ComponentName,
    val label: String,
    /** Ready-made 16:9 artwork: the app's own TV banner, or one built from its icon. */
    val art: Bitmap?,
    val tint: Int,
    val leanback: Boolean,
) {
    val key: String = "app:" + component.flattenToShortString()
    val pkg: String get() = component.packageName
}

object Apps {
    /** Apps worth pinning automatically on first run, best first. */
    val STARTER_PACKAGES = listOf(
        "com.netflix.ninja", "com.netflix.mediaclient",
        "com.google.android.youtube.tv", "com.teamsmart.videomanager.tv",
        "com.liskovsoft.smarttubetv.beta", "com.google.android.youtube",
        "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient", "com.amazon.avod",
        "com.disney.disneyplus", "com.spotify.tv.android", "com.spotify.music",
        "com.plexapp.android", "org.xbmc.kodi", "org.videolan.vlc",
    )

    /**
     * Every launchable app except this one. TV-style (leanback) entries win over the
     * phone-style entry of the same package, because they carry a proper 16:9 banner.
     * Call off the main thread: icons are rasterised here.
     */
    fun load(ctx: Context, bannerW: Int, bannerH: Int, iconSize: Int): List<AppEntry> {
        val pm = ctx.packageManager
        val out = ArrayList<AppEntry>()
        val seenComponents = HashSet<ComponentName>()
        val leanbackPackages = HashSet<String>()

        fun collect(category: String, leanback: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val found: List<ResolveInfo> = try {
                pm.queryIntentActivities(intent, 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in found) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName) continue
                if (!leanback && ai.packageName in leanbackPackages) continue
                val cn = ComponentName(ai.packageName, ai.name)
                if (!seenComponents.add(cn)) continue
                if (leanback) leanbackPackages += ai.packageName

                val label = try {
                    ri.loadLabel(pm)?.toString()
                } catch (e: Exception) {
                    null
                }?.trim().takeUnless { it.isNullOrEmpty() } ?: ai.packageName

                val bannerDrawable = if (!leanback) null else try {
                    ai.loadBanner(pm) ?: ai.applicationInfo?.loadBanner(pm)
                } catch (e: Exception) {
                    null
                }
                val banner = bannerDrawable?.let { rasterize(it, bannerW, bannerH) }
                val icon = if (banner != null) null else try {
                    ri.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }?.let { rasterize(it, iconSize, iconSize) }
                val tint = (banner ?: icon)?.let { Tint.from(it) } ?: Tint.fromText(ai.packageName)
                val art = banner
                    ?: icon?.let { Art.tile(it, bannerW, bannerH) }
                    ?: Art.monogram(label.take(1).uppercase(), tint, bannerW, bannerH)
                out += AppEntry(cn, label, art, tint, leanback)
            }
        }

        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)
        out.sortBy { it.label.lowercase() }
        return out
    }

    private fun rasterize(d: Drawable, w: Int, h: Int): Bitmap? = try {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        bmp
    } catch (e: Throwable) {
        null
    }
}
