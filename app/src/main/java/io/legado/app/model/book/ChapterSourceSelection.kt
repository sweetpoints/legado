package io.legado.app.model.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter

internal class ChapterSourceAutomationSession(
    val id: Long,
    val originalBook: Book,
    val chapters: List<BookChapter>,
    val targetBook: Book,
    val targetToc: List<BookChapter>,
) {
    var position: Int = 0
        private set

    var stopAfterCurrent: Boolean = false
        private set

    val currentChapter: BookChapter?
        get() = chapters.getOrNull(position)

    val total: Int
        get() = chapters.size

    fun advance(expectedChapterIndex: Int): Boolean {
        if (currentChapter?.index != expectedChapterIndex) return false
        position++
        return true
    }

    fun requestStopAfterCurrent() {
        stopAfterCurrent = true
    }
}

internal fun chapterSourceAutomationRange(
    chapters: List<BookChapter>,
    start: Int,
    endInclusive: Int,
): List<BookChapter> {
    val contentChapters = chapters.filterNot { it.isVolume }
    if (start !in 1..contentChapters.size || endInclusive !in start..contentChapters.size) {
        return emptyList()
    }
    return contentChapters.subList(start - 1, endInclusive)
}

internal class ChapterSourceProgress {
    var chapterIndex: Int = 0
        private set

    var chapterTitle: String = ""
        private set

    var isFinished: Boolean = false
        private set

    private var initialized = false

    fun initialize(chapterIndex: Int, chapterTitle: String) {
        if (initialized) return
        initialized = true
        this.chapterIndex = chapterIndex
        this.chapterTitle = chapterTitle
    }

    fun moveTo(chapter: BookChapter) {
        initialized = true
        isFinished = false
        chapterIndex = chapter.index
        chapterTitle = chapter.title
    }

    fun finish() {
        isFinished = true
    }

    fun currentChapter(chapters: List<BookChapter>): BookChapter? {
        return if (isFinished) null else chapters.firstOrNull { it.index == chapterIndex }
    }

    fun advance(chapters: List<BookChapter>, chapter: BookChapter): BookChapter? {
        val nextChapter = nextChapterSourceOriginal(chapters, chapter.index)
        isFinished = nextChapter == null
        if (nextChapter != null) {
            chapterIndex = nextChapter.index
            chapterTitle = nextChapter.title
        }
        return nextChapter
    }
}

internal fun nextChapterSourceOriginal(
    chapters: List<BookChapter>,
    currentIndex: Int,
): BookChapter? = chapters.firstOrNull { !it.isVolume && it.index > currentIndex }

internal fun selectedChapterSourceItems(
    chapters: List<BookChapter>,
    selectedIndices: Set<Int>,
): List<Pair<BookChapter, String?>> = chapters.mapIndexedNotNull { position, chapter ->
    if (!chapter.isVolume && chapter.index in selectedIndices)
        chapter to chapters.getOrNull(position + 1)?.url
    else null
}
