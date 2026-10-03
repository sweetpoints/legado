package io.legado.app.model.webBook

import io.legado.app.data.entities.SearchBook

/** Complete result metadata; callers persist this payload privately rather than in a Bundle. */
data class BookSearchResult(
    val bookUrl: String, val origin: String, val originName: String, val type: Int,
    val name: String, val author: String, val kind: String?, val coverUrl: String?,
    val intro: String?, val wordCount: String?, val latestChapterTitle: String?, val tocUrl: String,
    val time: Long, val variable: String?, val originOrder: Int, val chapterWordCountText: String?,
    val chapterWordCount: Int, val respondTime: Int, val origins: List<String>,
    val infoHtml: String?, val tocHtml: String?
) {
    // The legacy DiffUtil identity is name + author; length framing avoids ambiguous concatenation.
    val id: String get() = "${name.length}:$name${author.length}:$author"
    fun toSearchBook(): SearchBook = SearchBook(bookUrl, origin, originName, type, name, author, kind,
        coverUrl, intro, wordCount, latestChapterTitle, tocUrl, time, variable, originOrder,
        chapterWordCountText, chapterWordCount, respondTime).also { row ->
        row.origins.clear(); row.origins.addAll(origins); row.infoHtml = infoHtml; row.tocHtml = tocHtml
    }
    companion object {
        fun from(row: SearchBook): BookSearchResult = BookSearchResult(row.bookUrl, row.origin,
            row.originName, row.type, row.name, row.author, row.kind, row.coverUrl, row.intro,
            row.wordCount, row.latestChapterTitle, row.tocUrl, row.time, row.variable, row.originOrder,
            row.chapterWordCountText, row.chapterWordCount, row.respondTime, row.origins.toList(),
            row.infoHtml, row.tocHtml)
    }
}
fun filterBookSearchSnapshots(results: List<BookSearchResult>, filter: String): List<BookSearchResult> {
    val terms = filter.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
    if (terms.isEmpty()) return results
    return results.filter { row -> terms.none { term -> row.name.contains(term, true) || row.author.contains(term, true) || row.kind.orEmpty().contains(term, true) } }
}
