package io.legado.app.data.association

import io.legado.app.utils.isJson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val sharedImportUrlRegex =
    Regex("""(?<!["'])https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

private val sharedImportUrlTrailingPunctuation =
    setOf(
        '.',
        ',',
        ';',
        ':',
        '!',
        '?',
        '\u3002',
        '\uff0c',
        '\uff1b',
        '\uff1a',
        '\uff01',
        '\uff1f',
        '\u3001',
    )

private val sharedImportUrlBrackets =
    listOf(
        '(' to ')',
        '[' to ']',
        '{' to '}',
        '\uff08' to '\uff09',
        '\u3010' to '\u3011',
        '\u300a' to '\u300b',
    )

fun associationSharedImportUrl(text: String): String? {
    if (text.isJson()) return null
    return sharedImportUrlRegex
        .findAll(text)
        .map { it.value.trimSharedImportUrlSuffix() }
        .filter { it.toHttpUrlOrNull() != null }
        .singleOrNull()
}

private fun String.trimSharedImportUrlSuffix(): String {
    var result = trimEnd { it in sharedImportUrlTrailingPunctuation }
    sharedImportUrlBrackets.forEach { (open, close) ->
        while (
            result.endsWith(close) && result.count { it == close } > result.count { it == open }
        ) {
            result = result.dropLast(1)
        }
    }
    return result
}
