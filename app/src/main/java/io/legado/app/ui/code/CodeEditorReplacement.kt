package io.legado.app.ui.code

import io.github.rosemoe.sora.text.PreserveCaseReplace
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.util.regex.RegexBackrefHelper
import io.github.rosemoe.sora.util.regex.RegexBackrefParser
import io.github.rosemoe.sora.util.regex.RegexBackrefToken
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal data class CodeEditorMatch(val start: Int, val end: Int)

internal data class CodeEditorReplacement(
    val source: String,
    val query: String,
    val regex: Boolean,
    val caseInsensitive: Boolean,
    val grammar: RegexBackrefGrammar?,
    val preserveCase: Boolean,
    val matches: List<CodeEditorMatch>,
)

/** Keeps pinned Sora's region, back-reference and preserve-case replacement semantics. */
internal suspend fun replaceCodeEditorMatches(
    request: CodeEditorReplacement,
    replacement: String,
): String {
    val result = StringBuilder(request.source)
    val pattern =
        if (request.regex && request.grammar != null)
            Pattern.compile(
                request.query,
                Pattern.MULTILINE or if (request.caseInsensitive) Pattern.CASE_INSENSITIVE else 0,
            )
        else null
    var tokens: List<RegexBackrefToken>? = null
    var offset = 0
    for (region in request.matches) {
        coroutineContext.ensureActive()
        var computed = replacement
        if (pattern != null) {
            val matcher = pattern.matcher(request.source.substring(region.start, region.end))
            // Sora re-matches each captured region. Context-sensitive patterns that cannot
            // match that isolated region are intentionally left unchanged, as in 0.24.6.
            if (!matcher.find()) continue
            val parsed =
                tokens
                    ?: RegexBackrefParser(request.grammar!!)
                        .parse(replacement, matcher.groupCount())
                        .also { tokens = it }
            computed = RegexBackrefHelper.computeReplacement(matcher, parsed)
        }
        val replacementLength = computed.length
        val start = region.start + offset
        val end = region.end + offset
        if (request.preserveCase) {
            computed =
                PreserveCaseReplace.getReplacementSimple(result.substring(start, end), computed)
        }
        result.replace(start, end, computed)
        offset += replacementLength - (region.end - region.start)
    }
    return result.toString()
}
