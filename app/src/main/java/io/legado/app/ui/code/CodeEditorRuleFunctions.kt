package io.legado.app.ui.code

import io.legado.app.constant.AppPattern

internal suspend fun formatRuleExpression(
    text: String,
    formatter: suspend (String) -> String?,
): String? {
    val matcher = AppPattern.EXP_PATTERN.matcher(text.trim())
    if (!matcher.matches()) return null
    val body = matcher.group(1)?.trim() ?: return null
    val formattedBody = if (body.isEmpty()) body else formatter(body) ?: body
    return "{{$formattedBody}}"
}

internal fun scriptSourceIndex(source: String, lineNumber: Int, columnNumber: Int): Int {
    if (lineNumber <= 0) return 0
    var lineStart = 0
    repeat(lineNumber - 1) {
        val lineEnd = source.indexOf('\n', lineStart)
        if (lineEnd < 0) return source.length
        lineStart = lineEnd + 1
    }
    val lineEnd = source.indexOf('\n', lineStart).takeIf { it >= 0 } ?: source.length
    return (lineStart + (columnNumber - 1).coerceAtLeast(0)).coerceAtMost(lineEnd)
}
