package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.mergeChapterSourceBody

internal typealias ChapterSourceAutomationSession =
    io.legado.app.model.book.ChapterSourceAutomationSession

internal typealias ChapterSourceProgress = io.legado.app.model.book.ChapterSourceProgress

internal fun chapterSourceAutomationRange(
    chapters: List<BookChapter>,
    start: Int,
    endInclusive: Int,
) = io.legado.app.model.book.chapterSourceAutomationRange(chapters, start, endInclusive)

internal fun nextChapterSourceOriginal(chapters: List<BookChapter>, currentIndex: Int) =
    io.legado.app.model.book.nextChapterSourceOriginal(chapters, currentIndex)

internal fun selectedChapterSourceItems(chapters: List<BookChapter>, selectedIndices: Set<Int>) =
    io.legado.app.model.book.selectedChapterSourceItems(chapters, selectedIndices)

internal fun mergeChapterSourceContents(contents: List<String>) = mergeChapterSourceBody(contents)
