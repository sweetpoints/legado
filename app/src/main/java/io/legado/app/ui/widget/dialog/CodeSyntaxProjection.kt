package io.legado.app.ui.widget.dialog

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.legado.app.model.analyzeRule.*
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal data class CodeSyntaxColors(
    val legado: Color,
    val json: Color,
    val escapedNewline: Color,
    val operation: Color,
    val javascript: Color,
)

/** Pure UTF-16 projection preserves every offset used by editing, search and Activity results. */
internal suspend fun projectCodeSyntax(
    text: String,
    colors: CodeSyntaxColors,
    visibleRange: IntRange = text.indices,
): AnnotatedString {
    val visibleStart = visibleRange.first.coerceIn(0, text.length)
    val visibleEnd = (visibleRange.last + 1).coerceIn(visibleStart, text.length)
    val rules: List<Pair<Pattern, Color>> =
        listOf(
            legadoPattern to colors.legado,
            jsonPattern to colors.json,
            wrapPattern to colors.escapedNewline,
            operationPattern to colors.operation,
            jsPattern to colors.javascript,
        )
    // Fixed rule tokens fit in this context; JSON's ASCII key pattern is the only
    // unbounded token. Include its entire run and adjacent quote/colon when clipping it.
    var scanStart = (visibleStart - 16).coerceAtLeast(0)
    var scanEnd = (visibleEnd + 16).coerceAtMost(text.length)
    while (scanStart > 0 && text[scanStart - 1].isJsonKeyCharacter()) scanStart--
    while (scanEnd < text.length && text[scanEnd].isJsonKeyCharacter()) scanEnd++
    scanStart = (scanStart - 1).coerceAtLeast(0)
    // Nonoverlapping pair tokens (||, &&, %%, @@) inherit their phase from the
    // beginning of a repeated-symbol run, which can be arbitrarily far away.
    if (scanStart < text.length && text[scanStart] in "|&%@") {
        val symbol = text[scanStart]
        while (scanStart > 0 && text[scanStart - 1] == symbol) scanStart--
    }
    scanEnd = (scanEnd + 2).coerceAtMost(text.length)
    val jobContext = coroutineContext
    jobContext.ensureActive()
    return buildAnnotatedString {
        append(text)
        rules.forEach { (pattern, color) ->
            val matcher = pattern.matcher(text)
                .region(scanStart, scanEnd)
                // Word boundaries must see the original text beyond the scanning region.
                .useTransparentBounds(true)
            var count = 0
            while (matcher.find()) {
                if (++count % 64 == 0) jobContext.ensureActive()
                if (matcher.start() >= visibleEnd) break
                val start = maxOf(matcher.start(), visibleStart)
                val end = minOf(matcher.end(), visibleEnd)
                if (start < end) addStyle(SpanStyle(color = color), start, end)
            }
        }
    }
}

private fun Char.isJsonKeyCharacter(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
