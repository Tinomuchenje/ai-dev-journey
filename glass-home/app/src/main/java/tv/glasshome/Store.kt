package tv.glasshome

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** A website pinned to the home screen. */
class Site(val id: String, var name: String, var url: String, var tvMode: Boolean) {
    val key get() = "web:$id"

    val host: String
        get() = (Uri.parse(url).host ?: url).removePrefix("www.")

    val monogram: String
        get() = (name.trim().firstOrNull() ?: host.firstOrNull() ?: '?').uppercaseChar().toString()
}

/** Everything the launcher remembers. Small enough that SharedPreferences is plenty. */
class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("glass", Context.MODE_PRIVATE)

    /** Ordered keys of pinned items: "app:<component>" or "web:<id>". */
    var favorites: List<String>
        get() = readList("favorites")
        set(v) = writeList("favorites", v)

    /** Keys of apps the user never wants to see. */
    var hidden: Set<String>
        get() = readList("hidden").toSet()
        set(v) = writeList("hidden", v.toList())

    var sites: List<Site>
        get() {
            val out = ArrayList<Site>()
            val arr = try {
                JSONArray(sp.getString("sites", "[]"))
            } catch (e: Exception) {
                JSONArray()
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += Site(
                    o.optString("id"), o.optString("name"), o.optString("url"), o.optBoolean("tv")
                )
            }
            return out
        }
        set(v) {
            val arr = JSONArray()
            for (s in v) {
                arr.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url).put("tv", s.tvMode))
            }
            sp.edit().putString("sites", arr.toString()).apply()
        }

    var wallpaper: Int
        get() = sp.getInt("wallpaper", 0)
        set(v) = sp.edit().putInt("wallpaper", v).apply()

    /** Browser text size in percent; 0 means "pick a sensible default for this panel". */
    var textZoom: Int
        get() = sp.getInt("textZoom", 0)
        set(v) = sp.edit().putInt("textZoom", v).apply()

    var desktopSites: Boolean
        get() = sp.getBoolean("desktop", true)
        set(v) = sp.edit().putBoolean("desktop", v).apply()

    /** True: websites open in the built-in pointer browser even if another browser is installed. */
    var preferBuiltInBrowser: Boolean
        get() = sp.getBoolean("builtInBrowser", false)
        set(v) = sp.edit().putBoolean("builtInBrowser", v).apply()

    /** True: no decorative movement, for TVs that struggle with it. */
    var reducedMotion: Boolean
        get() = sp.getBoolean("reducedMotion", false)
        set(v) = sp.edit().putBoolean("reducedMotion", v).apply()

    var seeded: Boolean
        get() = sp.getBoolean("seeded", false)
        set(v) = sp.edit().putBoolean("seeded", v).apply()

    fun isFavorite(key: String) = key in favorites

    fun toggleFavorite(key: String) {
        val f = favorites.toMutableList()
        if (!f.remove(key)) f += key
        favorites = f
    }

    fun addSite(name: String, url: String, tvMode: Boolean): Site {
        val s = Site(java.lang.Long.toString(System.currentTimeMillis(), 36), name, url, tvMode)
        sites = sites + s
        return s
    }

    fun updateSite(site: Site) {
        sites = sites.map { if (it.id == site.id) site else it }
    }

    fun removeSite(site: Site) {
        sites = sites.filter { it.id != site.id }
        favorites = favorites - site.key
    }

    private fun readList(name: String): List<String> {
        val arr = try {
            JSONArray(sp.getString(name, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return List(arr.length()) { arr.optString(it) }
    }

    private fun writeList(name: String, v: List<String>) {
        sp.edit().putString(name, JSONArray(v).toString()).apply()
    }

    companion object {
        /** Turns whatever was typed into something loadable; plain words become a web search. */
        fun normalizeUrl(input: String): String {
            val t = input.trim()
            if (t.isEmpty()) return t
            if (t.contains("://")) return t
            val looksLikeHost = !t.contains(' ') && (t.contains('.') || t.startsWith("localhost"))
            return if (looksLikeHost) "https://$t" else "https://www.google.com/search?q=" + Uri.encode(t)
        }
    }
}
