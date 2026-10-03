package io.legado.app.model.book

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reader-compatible positions, with no Android text objects or large chapter bodies. */
internal data class ContentSearchMatch(
    val id: String,
    val resultCount: Int = 0,
    val resultCountWithinChapter: Int = 0,
    val resultText: String = "",
    val chapterTitle: String = "",
    val query: String = "",
    val pageSize: Int = 0,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val queryIndexInResult: Int = 0,
    val queryIndexInChapter: Int = 0,
    val isRegex: Boolean = false,
)

internal suspend fun findContentSearchMatches(
    content: String,
    query: String,
    chapterIndex: Int,
    chapterTitle: String,
    regex: Boolean,
): List<ContentSearchMatch> {
    if (query.isBlank()) return emptyList()
    val positions = mutableListOf<Int>()
    if (regex) {
        try {
            Regex(query).findAll(content).forEach {
                currentCoroutineContext().ensureActive()
                positions += it.range.first
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
    } else {
        var position = content.indexOf(query)
        while (position >= 0) {
            currentCoroutineContext().ensureActive()
            positions += position
            position = content.indexOf(query, position + query.length)
        }
    }
    return positions.mapIndexed { ordinal, position ->
        currentCoroutineContext().ensureActive()
        val start = (position - 20).coerceAtLeast(0)
        val end =
            (position.toLong() + query.length + 20).coerceAtMost(content.length.toLong()).toInt()
        ContentSearchMatch(
            contentSearchIdentity(query, chapterIndex, position, ordinal),
            resultCountWithinChapter = ordinal,
            resultText = content.substring(start, end),
            chapterTitle = chapterTitle,
            query = query,
            chapterIndex = chapterIndex,
            queryIndexInResult = position - start,
            queryIndexInChapter = position,
            isRegex = regex,
        )
    }
}

internal fun contentSearchIdentity(query: String, chapter: Int, position: Int, ordinal: Int) =
    "$chapter:$position:$ordinal:" +
        MessageDigest.getInstance("SHA-256")
            .digest(query.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }

/** The previous renderer prefers the match at/after the 20-character snippet offset. */
internal fun contentSearchHighlight(match: ContentSearchMatch): IntRange? {
    if (match.query.isBlank()) return null
    return if (match.isRegex) {
        try {
            val regex = Regex(match.query)
            val found =
                regex.find(match.resultText, minOf(20, match.resultText.length))
                    ?: regex.find(match.resultText)
            found?.range?.takeUnless { it.isEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    } else {
        val index =
            match.resultText.indexOf(match.query, 20).takeIf { it >= 0 }
                ?: match.resultText.indexOf(match.query)
        index.takeIf { it >= 0 }?.let { it until it + match.query.length }
    }
}
