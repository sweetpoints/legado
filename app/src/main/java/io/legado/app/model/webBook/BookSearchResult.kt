package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook

/** Complete result metadata; callers persist this payload privately rather than in a Bundle. */
data class BookSearchResult(
    val bookUrl: String,
    val origin: String,
    val originName: String,
    val type: Int,
    val name: String,
    val author: String,
    val kind: String?,
    val coverUrl: String?,
    val intro: String?,
    val wordCount: String?,
    val latestChapterTitle: String?,
    val tocUrl: String,
    val time: Long,
    val variable: String?,
    val originOrder: Int,
    val chapterWordCountText: String?,
    val chapterWordCount: Int,
    val respondTime: Int,
    val origins: List<String>,
    val infoHtml: String?,
    val tocHtml: String?,
) {
    // Legacy DiffUtil uses name + author; length framing prevents concatenation collisions.
    val id: String
        get() = "${name.length}:$name${author.length}:$author"

    fun toSearchBook(): SearchBook {
        return SearchBook(
            bookUrl = bookUrl,
            origin = origin,
            originName = originName,
            type = type,
            name = name,
            author = author,
            kind = kind,
            coverUrl = coverUrl,
            intro = intro,
            wordCount = wordCount,
            latestChapterTitle = latestChapterTitle,
            tocUrl = tocUrl,
            time = time,
            variable = variable,
            originOrder = originOrder,
            chapterWordCountText = chapterWordCountText,
            chapterWordCount = chapterWordCount,
            respondTime = respondTime,
        ).also { book ->
            book.origins.clear()
            book.origins.addAll(origins)
            book.infoHtml = infoHtml
            book.tocHtml = tocHtml
        }
    }

    companion object {
        fun from(book: SearchBook): BookSearchResult {
            return BookSearchResult(
                bookUrl = book.bookUrl,
                origin = book.origin,
                originName = book.originName,
                type = book.type,
                name = book.name,
                author = book.author,
                kind = book.kind,
                coverUrl = book.coverUrl,
                intro = book.intro,
                wordCount = book.wordCount,
                latestChapterTitle = book.latestChapterTitle,
                tocUrl = book.tocUrl,
                time = book.time,
                variable = book.variable,
                originOrder = book.originOrder,
                chapterWordCountText = book.chapterWordCountText,
                chapterWordCount = book.chapterWordCount,
                respondTime = book.respondTime,
                origins = book.origins.toList(),
                infoHtml = book.infoHtml,
                tocHtml = book.tocHtml,
            )
        }
    }
}

fun filterBookSearchSnapshots(
    results: List<BookSearchResult>,
    filter: String,
): List<BookSearchResult> {
    val terms = filter.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
    if (terms.isEmpty()) return results

    return results.filter { result ->
        terms.none { term ->
            result.name.contains(term, ignoreCase = true) ||
                result.author.contains(term, ignoreCase = true) ||
                result.kind.orEmpty().contains(term, ignoreCase = true)
        }
    }
}
