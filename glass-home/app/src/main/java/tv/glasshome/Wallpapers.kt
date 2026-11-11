package tv.glasshome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Backdrops are generated in code: a dark base with a few big, soft blobs of colour.
 * They are rendered once into a small bitmap, so the GPU only ever draws one textured
 * quad behind the UI - and because they are already "out of focus", translucent panes
 * on top of them read as frosted glass without any real-time blur.
 */
object Wallpapers {

    private class Blob(val x: Float, val y: Float, val r: Float, val color: Int)

    private class Style(val name: String, val top: Int, val bottom: Int, val blobs: List<Blob>)

    private fun c(hex: Long) = hex.toInt()

    private val styles = listOf(
        Style(
            "Aurora", c(0xFF0A1030), c(0xFF140B36), listOf(
                Blob(0.14f, 0.18f, 0.62f, c(0xFF2F6BFF)),
                Blob(0.86f, 0.12f, 0.55f, c(0xFF8B5CF6)),
                Blob(0.74f, 0.92f, 0.58f, c(0xFF12B8A6)),
                Blob(0.12f, 0.98f, 0.5f, c(0xFFDB3B8E)),
            )
        ),
        Style(
            "Dusk", c(0xFF1B0B2E), c(0xFF2A0D1C), listOf(
                Blob(0.1f, 0.1f, 0.6f, c(0xFF5B34D6)),
                Blob(0.9f, 0.2f, 0.55f, c(0xFFD9318F)),
                Blob(0.72f, 1f, 0.62f, c(0xFFFF7A35)),
                Blob(0.14f, 0.95f, 0.45f, c(0xFFB8336A)),
            )
        ),
        Style(
            "Ocean", c(0xFF03142B), c(0xFF042A3C), listOf(
                Blob(0.12f, 0.15f, 0.6f, c(0xFF1D5BFF)),
                Blob(0.88f, 0.1f, 0.5f, c(0xFF0EA5E9)),
                Blob(0.8f, 0.95f, 0.6f, c(0xFF10B981)),
                Blob(0.15f, 1f, 0.5f, c(0xFF22C3EE)),
            )
        ),
        Style(
            "Forest", c(0xFF071A14), c(0xFF10200F), listOf(
                Blob(0.1f, 0.12f, 0.6f, c(0xFF0F9D6B)),
                Blob(0.9f, 0.15f, 0.5f, c(0xFF2C7A7B)),
                Blob(0.78f, 0.98f, 0.58f, c(0xFFB5A21E)),
                Blob(0.14f, 1f, 0.5f, c(0xFF2F855A)),
            )
        ),
        Style(
            "Graphite", c(0xFF101319), c(0xFF191C24), listOf(
                Blob(0.12f, 0.1f, 0.6f, c(0xFF55617A)),
                Blob(0.9f, 0.2f, 0.5f, c(0xFF3D4760)),
                Blob(0.75f, 1f, 0.6f, c(0xFF4A5568)),
                Blob(0.1f, 1f, 0.45f, c(0xFF2F3B52)),
            )
        ),
    )

    val count get() = styles.size

    fun name(index: Int) = styles[index.mod(styles.size)].name

    fun make(index: Int, w: Int = 960, h: Int = 540): Bitmap {
        val s = styles[index.mod(styles.size)]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, w * 0.3f, h.toFloat(), s.top, s.bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        for (b in s.blobs) {
            val r = b.r * w
            p.shader = RadialGradient(
                b.x * w, b.y * h, r,
                intArrayOf(withAlpha(b.color, 0.78f), withAlpha(b.color, 0.34f), withAlpha(b.color, 0f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        // Gentle vignette so white text stays readable at the top and bottom.
        p.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x52000000, 0x1F000000, 0x47000000),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.argb(20, 0, 0, 0)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return bmp
    }
}
