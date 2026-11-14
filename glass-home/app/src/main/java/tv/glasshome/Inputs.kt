package tv.glasshome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputManager

/** One way of getting to something plugged into the TV, or to live television. */
class InputChoice(val label: String, val intent: Intent)

/**
 * Finds how this particular TV switches inputs. There is no single way: certified Android
 * TVs register each HDMI port with the system, while generic firmware keeps its own
 * source-picker screen. Each known route is tried and only the ones that exist are offered.
 */
object Inputs {
    // Source pickers of generic TV firmware, as recorded in the device notes of
    // github.com/Gathin23/cleanyourtv (CVTE boards: DTV, ATV, AV, HDMI 1-3).
    private val PANELS = listOf(
        "com.cvte.tv.globalui/.InputSourceActivity",
    )
    private val LIVE_TV = listOf(
        "com.cvte.tv.setting/.TvSettingActivity",
    )

    fun find(ctx: Context): List<InputChoice> {
        val pm = ctx.packageManager
        val out = ArrayList<InputChoice>()
        fun resolves(i: Intent) = try {
            pm.resolveActivity(i, 0) != null
        } catch (e: Exception) {
            false
        }
        fun component(name: String) = Intent().setComponent(ComponentName.unflattenFromString(name))

        val panel = PANELS.map { component(it) }.firstOrNull { resolves(it) }
            ?: Intent("com.android.tv.action.VIEW_INPUTS").takeIf { resolves(it) }
        if (panel != null) out += InputChoice("HDMI and other inputs", panel)

        val live = LIVE_TV.map { component(it) }.firstOrNull { resolves(it) }
            ?: Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI).takeIf { resolves(it) }
        if (live != null) out += InputChoice("Live TV", live)

        // Ports the TV has registered with Android itself.
        try {
            val tv = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            for (info in tv?.tvInputList.orEmpty()) {
                if (!info.isPassthroughInput) continue
                val view = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))
                if (!resolves(view)) continue
                val name = info.loadLabel(ctx)?.toString()?.takeIf { it.isNotBlank() } ?: "Input"
                out += InputChoice(name, view)
            }
        } catch (e: Exception) {
            // no TV input framework on this device
        }
        return out
    }
}
