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
    val jobContext = coroutineContext
    return buildAnnotatedString {
        append(text)
        rules.forEach { (pattern, color) ->
            val matcher = pattern.matcher(text)
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
