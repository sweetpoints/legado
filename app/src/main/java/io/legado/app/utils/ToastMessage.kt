package io.legado.app.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextGeometricTransform
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import kotlin.math.abs

internal data class ToastMessage(
    val annotatedText: AnnotatedString,
    val inlineImages: Map<String, ToastInlineImage>,
)

internal data class ToastInlineImage(
    val bitmap: Bitmap,
    val widthPx: Int,
    val heightPx: Int,
)

/** Converts Android spans to Compose styles and rasterizes replacement spans as inline content. */
internal fun CharSequence?.toToastMessage(
    baseTextSizePx: Float,
    scaledDensity: Float,
    color: Int,
): ToastMessage {
    val source = this ?: ""
    val spanned = source as? Spanned
    if (spanned == null || spanned.isEmpty()) {
        return ToastMessage(AnnotatedString(source.toString()), emptyMap())
    }

    val basePaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = baseTextSizePx
            typeface = Typeface.DEFAULT
            isSubpixelText = true
        }
    val replacements =
        spanned
            .getSpans(0, spanned.length, ReplacementSpan::class.java)
            .mapNotNull { span ->
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span)
                if (start < 0 || end <= start) null else ReplacementRange(span, start, end)
            }
            .sortedBy(ReplacementRange::start)
            .fold(mutableListOf<ReplacementRange>()) { accepted, candidate ->
                if (accepted.lastOrNull()?.end?.let { candidate.start < it } != true) {
                    accepted += candidate
                }
                accepted
            }

    val inlineImages = linkedMapOf<String, ToastInlineImage>()
    val text = AnnotatedString.Builder()
    var sourceIndex = 0
    var replacementIndex = 0
    while (sourceIndex < spanned.length) {
        val replacement = replacements.getOrNull(replacementIndex)
        if (replacement != null && replacement.start == sourceIndex) {
            val key = "toast-replacement-$replacementIndex"
            val image = replacement.rasterize(spanned, basePaint)
            inlineImages[key] = image
            text.appendInlineContent(
                key,
                spanned.subSequence(replacement.start, replacement.end).toString(),
            )
            sourceIndex = replacement.end
            replacementIndex++
        } else {
            val nextReplacementStart = replacement?.start ?: spanned.length
            val nextIndex = nextReplacementStart.coerceAtMost(spanned.length)
            text.append(spanned.subSequence(sourceIndex, nextIndex))
            sourceIndex = nextIndex
        }
    }

    spanned.getSpans(0, spanned.length, CharacterStyle::class.java).forEach { span ->
        if (span is ReplacementSpan) return@forEach
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        if (start < 0 || end <= start || end > text.length) return@forEach
        val style = span.toComposeSpanStyle(basePaint, scaledDensity) ?: return@forEach
        text.addStyle(style, start, end)
    }

    return ToastMessage(text.toAnnotatedString(), inlineImages)
}

private data class ReplacementRange(val span: ReplacementSpan, val start: Int, val end: Int) {
    fun rasterize(text: Spanned, basePaint: TextPaint): ToastInlineImage {
        val paint = TextPaint(basePaint)
        text.getSpans(start, end, CharacterStyle::class.java).forEach { span ->
            if (span !is ReplacementSpan && span is MetricAffectingSpan)
                span.updateMeasureState(paint)
            if (span !is ReplacementSpan) span.updateDrawState(paint)
        }
        val metrics = Paint.FontMetricsInt()
        val width = span.getSize(paint, text, start, end, metrics).coerceAtLeast(1)
        val height = (metrics.bottom - metrics.top).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        span.draw(canvas, text, start, end, 0f, 0, -metrics.top, height, paint)
        return ToastInlineImage(bitmap, width, height)
    }
}

private fun CharacterStyle.toComposeSpanStyle(
    basePaint: TextPaint,
    scaledDensity: Float,
): SpanStyle? {
    val paint = TextPaint(basePaint)
    if (this is MetricAffectingSpan) updateMeasureState(paint)
    updateDrawState(paint)

    val changedColor = paint.color.takeIf { it != basePaint.color }?.let(::Color)
    val changedBackground = (this as? BackgroundColorSpan)?.backgroundColor?.let(::Color)
    val changedSize =
        paint.textSize
            .takeIf { abs(it - basePaint.textSize) > 0.01f }
            ?.let { (it / scaledDensity).sp }
    val changedTypeface = paint.typeface
    val changedWeight =
        changedTypeface
            ?.isBold
            ?.takeIf { it != basePaint.typeface?.isBold }
            ?.let { if (it) FontWeight.Bold else FontWeight.Normal }
    val changedStyle =
        changedTypeface
            ?.isItalic
            ?.takeIf { it != basePaint.typeface?.isItalic }
            ?.let { if (it) FontStyle.Italic else FontStyle.Normal }
    val family = (this as? android.text.style.TypefaceSpan)?.family?.toComposeFontFamily()
    val decoration =
        when {
            paint.isUnderlineText && paint.isStrikeThruText ->
                TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            paint.isUnderlineText -> TextDecoration.Underline
            paint.isStrikeThruText -> TextDecoration.LineThrough
            else -> null
        }
    val baselineShift =
        when (this) {
            is android.text.style.SuperscriptSpan -> BaselineShift.Superscript
            is android.text.style.SubscriptSpan -> BaselineShift.Subscript
            else -> null
        }
    val geometricTransform =
        paint.textScaleX.takeIf { abs(it - 1f) > 0.001f }?.let(::TextGeometricTransform)

    if (
        changedColor == null &&
            changedBackground == null &&
            changedSize == null &&
            changedWeight == null &&
            changedStyle == null &&
            family == null &&
            decoration == null &&
            baselineShift == null &&
            geometricTransform == null
    )
        return null

    return SpanStyle(
        color = changedColor ?: Color.Unspecified,
        background = changedBackground ?: Color.Unspecified,
        fontSize = changedSize ?: androidx.compose.ui.unit.TextUnit.Unspecified,
        fontWeight = changedWeight,
        fontStyle = changedStyle,
        fontFamily = family,
        textDecoration = decoration,
        baselineShift = baselineShift,
        textGeometricTransform = geometricTransform,
    )
}

private fun String.toComposeFontFamily(): FontFamily? =
    when (lowercase()) {
        "serif" -> FontFamily.Serif
        "monospace",
        "monospaced" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        "sans-serif",
        "sans",
        "sans-serif-condensed",
        "sans-serif-light",
        "sans-serif-medium",
        "sans-serif-black" -> FontFamily.SansSerif
        else -> FontFamily.Default
    }
