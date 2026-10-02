package io.legado.app.data.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import androidx.collection.LruCache
import androidx.core.graphics.createBitmap
import io.legado.app.data.repository.CoverConfiguration
import io.legado.app.data.repository.CoverRequest
import io.legado.app.data.repository.normalizeComposeCoverText
import io.legado.app.model.CoverFontSizes
import io.legado.app.utils.textHeight
import io.legado.app.utils.toStringArray

private const val HORIZONTAL_TITLE_MAX_LINES = 4

data class CoverTitleKey(val name: String, val author: String?, val width: Int, val height: Int,
    val horizontal: Boolean, val drawAuthor: Boolean, val adaptive: Boolean,
    val backgroundColor: Int, val accentColor: Int, val sizes: CoverFontSizes?, val fontKey: String)

/** View-free rasterization preserving the existing vertical/horizontal cover typography. */
class CoverTitleRenderer {
    private val cache = LruCache<CoverTitleKey, Bitmap>(33)
    @Synchronized fun render(request: CoverRequest, configuration: CoverConfiguration, width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0)
        val name = normalizeComposeCoverText(request.name, configuration.keepPunctuation).orEmpty()
        val author = normalizeComposeCoverText(request.author, configuration.keepPunctuation)
        val key = CoverTitleKey(name, author, width, height, configuration.horizontal, configuration.drawAuthor,
            configuration.adaptive, configuration.backgroundColor, configuration.accentColor, configuration.fontSizes, configuration.fontCacheKey)
        cache[key]?.let { return it }
        return generateCoverBitmap(name, author, width, height, configuration.horizontal, configuration.drawAuthor,
            configuration.backgroundColor, configuration.accentColor, configuration.adaptive, configuration.fontSizes, configuration.typeface)
            .let { bitmap -> checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)).also { bitmap.recycle() } }
            .also { cache.put(key, it) }
    }
    private fun generateCoverBitmap(
        name: String?,
        author: String?,
        renderWidth: Int,
        renderHeight: Int,
        horizontal: Boolean,
        drawAuthor: Boolean,
        backgroundColor: Int,
        accentColor: Int,
        adaptiveTitle: Boolean,
        fontSizes: CoverFontSizes?,
        fontTypeface: Typeface?,
    ): Bitmap {
        val viewWidth = renderWidth.toFloat()
        val viewHeight = renderHeight.toFloat()
        val bitmap = createBitmap(renderWidth, renderHeight)
        val bitmapCanvas = Canvas(bitmap)
        var startX = renderWidth * 0.2f
        var startY = viewHeight * 0.2f
        if (horizontal) {
            drawHorizontalTextCover(
                bitmapCanvas,
                name,
                author,
                backgroundColor,
                accentColor,
                drawAuthor,
                viewWidth,
                viewHeight,
                adaptiveTitle,
                fontSizes,
                fontTypeface,
            )
            return bitmap
        }
        val namePaint = TextPaint().apply {
            typeface = fontTypeface ?: Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        name?.toStringArray()?.let { name ->
            var line = 0
            namePaint.textSize = viewWidth / 7
            fontSizes?.let { namePaint.textSize *= it.titleLarge / 100f }
            if (adaptiveTitle && name.size * namePaint.textHeight > viewHeight * 0.6f) {
                namePaint.textSize = fontSizes?.let { viewWidth / 7 * it.titleSmall / 100f }
                    ?: (viewWidth / 9)
            }
            namePaint.strokeWidth = namePaint.textSize / 6
            name.forEachIndexed { index, char ->
                if (fontSizes != null) namePaint.strokeWidth = namePaint.textSize / 6
                namePaint.color = backgroundColor
                namePaint.style = Paint.Style.STROKE
                bitmapCanvas.drawText(char, startX, startY, namePaint)
                namePaint.color = accentColor
                namePaint.style = Paint.Style.FILL
                bitmapCanvas.drawText(char, startX, startY, namePaint)
                startY += namePaint.textHeight
                if (startY > viewHeight * 0.9) {
                    if ((name.size - index - 1) == 1) {
                        startY -= namePaint.textHeight / 5
                        if (!adaptiveTitle) {
                            namePaint.textSize = fontSizes?.let { viewWidth / 7 * it.titleSmall / 100f }
                                ?: (viewWidth / 9)
                        }
                        return@forEachIndexed
                    }
                    startX += namePaint.textSize
                    line++
                    if (!adaptiveTitle) {
                        namePaint.textSize = fontSizes?.let { viewWidth / 7 * it.titleSmall / 100f }
                            ?: (viewWidth / 10)
                    }
                    startY = viewHeight * 0.2f + namePaint.textHeight * line
                } else if (startY > viewHeight * 0.8 && (name.size - index - 1) > 2) {
                    startX += namePaint.textSize
                    line++
                    if (!adaptiveTitle) {
                        namePaint.textSize = fontSizes?.let { viewWidth / 7 * it.titleSmall / 100f }
                            ?: (viewWidth / 10)
                    }
                    startY = viewHeight * 0.2f + namePaint.textHeight * line
                }
            }
        }
        if (!drawAuthor){
            return bitmap
        }
        val authorPaint = TextPaint(namePaint).apply {
            typeface = fontTypeface ?: Typeface.DEFAULT
        }
        author?.toStringArray()?.let { author ->
            authorPaint.textSize = viewWidth / 10
            fontSizes?.let {
                authorPaint.textSize *= it.authorLarge / 100f
                if (author.size * authorPaint.textHeight > viewHeight * 0.65f) {
                    authorPaint.textSize = viewWidth / 10 * it.authorSmall / 100f
                }
            }
            authorPaint.strokeWidth = authorPaint.textSize / 5
            startX = renderWidth * 0.8f
            var startY = viewHeight * 0.95f - author.size * authorPaint.textHeight
            startY = maxOf(startY, viewHeight * 0.3f)
            author.forEach {
                authorPaint.color = backgroundColor
                authorPaint.style = Paint.Style.STROKE
                bitmapCanvas.drawText(it, startX, startY, authorPaint)
                authorPaint.color = accentColor
                authorPaint.style = Paint.Style.FILL
                bitmapCanvas.drawText(it, startX, startY, authorPaint)
                startY += authorPaint.textHeight
                if (startY > viewHeight * 0.95) {
                    return@let
                }
            }
        }
        return bitmap
    }

    private fun drawHorizontalTextCover(
        canvas: Canvas,
        name: String?,
        author: String?,
        backgroundColor: Int,
        accentColor: Int,
        drawAuthor: Boolean,
        viewWidth: Float,
        viewHeight: Float,
        adaptiveTitle: Boolean,
        fontSizes: CoverFontSizes?,
        fontTypeface: Typeface?,
    ) {
        val basePaint = TextPaint().apply {
            isAntiAlias = true
            typeface = fontTypeface ?: Typeface.DEFAULT_BOLD
        }
        name?.takeIf { it.isNotEmpty() }?.let { title ->
            val titleWidth = (viewWidth * 0.78f).toInt().coerceAtLeast(1)
            val titlePaint = TextPaint(basePaint).apply {
                textAlign = Paint.Align.LEFT
                textSize = viewWidth / 7
                fontSizes?.let { textSize *= it.titleLarge / 100f }
                strokeWidth = textSize / 6
            }
            var titleLayout = horizontalTitleLayout(title, titlePaint, titleWidth)
            if (titleLayout.lineCount > 1 || titlePaint.measureText(title) > titleWidth) {
                val firstLineEnd = titleLayout.getLineEnd(0)
                titlePaint.textSize = fontSizes?.let { viewWidth / 7 * it.titleSmall / 100f }
                    ?: (viewWidth / 9)
                titlePaint.strokeWidth = titlePaint.textSize / 6
                val displayTitle = if (adaptiveTitle) title else SpannableString(title).apply {
                    val ratio = fontSizes?.let { it.titleLarge.toFloat() / it.titleSmall } ?: (9f / 7f)
                    setSpan(RelativeSizeSpan(ratio), 0, firstLineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                titleLayout = horizontalTitleLayout(displayTitle, titlePaint, titleWidth)
            }
            val titleX = (viewWidth - titleWidth) / 2f
            val titleY = viewHeight * 0.1f
            canvas.save()
            canvas.translate(titleX, titleY)
            titlePaint.color = backgroundColor
            titlePaint.style = Paint.Style.STROKE
            titleLayout.draw(canvas)
            titlePaint.color = accentColor
            titlePaint.style = Paint.Style.FILL
            titleLayout.draw(canvas)
            canvas.restore()
        }

        if (!drawAuthor) return
        author?.takeIf { it.isNotEmpty() }?.let { authorText ->
            val authorWidth = viewWidth * 0.65f
            val authorPaint = TextPaint(basePaint).apply {
                typeface = fontTypeface ?: Typeface.DEFAULT
                textAlign = Paint.Align.RIGHT
                textSize = viewWidth / 10
                fontSizes?.let { textSize *= it.authorLarge / 100f }
                strokeWidth = textSize / 5
            }
            val smallSize = fontSizes?.let { viewWidth / 10 * it.authorSmall / 100f } ?: (viewWidth / 16)
            if (fontSizes != null && authorPaint.measureText(authorText) > authorWidth &&
                smallSize > authorPaint.textSize
            ) {
                authorPaint.textSize = smallSize
                authorPaint.strokeWidth = authorPaint.textSize / 5
            }
            while (authorPaint.textSize > smallSize &&
                authorPaint.measureText(authorText) > authorWidth
            ) {
                authorPaint.textSize = if (fontSizes == null) authorPaint.textSize - 0.5f
                    else maxOf(smallSize, authorPaint.textSize - 0.5f)
                authorPaint.strokeWidth = authorPaint.textSize / 5
            }
            val displayAuthor = TextUtils.ellipsize(
                authorText,
                authorPaint,
                authorWidth,
                TextUtils.TruncateAt.END
            ).toString()
            val authorX = viewWidth * 0.9f
            val authorY = viewHeight * 0.92f
            authorPaint.color = backgroundColor
            authorPaint.style = Paint.Style.STROKE
            canvas.drawText(displayAuthor, authorX, authorY, authorPaint)
            authorPaint.color = accentColor
            authorPaint.style = Paint.Style.FILL
            canvas.drawText(displayAuthor, authorX, authorY, authorPaint)
        }
    }

    private fun horizontalTitleLayout(
        title: CharSequence,
        paint: TextPaint,
        width: Int
    ): StaticLayout = StaticLayout.Builder
        .obtain(title, 0, title.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setIncludePad(false)
        .setMaxLines(HORIZONTAL_TITLE_MAX_LINES)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()

}
