package io.legado.app.ui.highlight.edit

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import io.legado.app.R
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.book.read.HighlightFillPreviewDrawable
import io.legado.app.utils.GSON
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.ceil

/** Reuses the reader's exact fill renderer; only immutable PNG bytes cross the UI boundary. */
internal class HighlightRuleFlutterPreview(private val resources: Resources) {
    private var previousKey: String? = null
    private var previous: Map<String, Any?> = emptyMap()
    fun render(style: HighlightStyle): Map<String, Any?> {
        val density = resources.displayMetrics.density
        val textPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        val textWidth = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = textPixels }.measureText(resources.getString(R.string.highlight_style))
        val width = ceil(textWidth + 24 * density).toInt().coerceAtLeast(1)
        val height = ceil(textPixels * 1.5f + 8 * density).toInt().coerceAtLeast(1)
        require(width in 1..2048 && height in 1..2048) { "Highlight preview dimensions unavailable" }
        val key = "${GSON.toJson(style)}|$density|$width|$height|$textPixels"
        if (key == previousKey) return previous
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = try {
            val drawable = HighlightFillPreviewDrawable(style, textPixels)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        } finally { bitmap.recycle() }
        check(bytes.size <= 256 * 1024) { "Highlight preview exceeds UI message bound" }
        previousKey = key
        previous = mapOf("png" to bytes, "width" to width / density.toDouble(), "height" to height / density.toDouble(),
            "textColor" to style.textColor, "inputSha256" to MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })
        return previous
    }
}
