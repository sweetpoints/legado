package io.legado.app.ui.book.manga

import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.entities.ReaderLoading

/** Detached values: the engine can later mutate imageCount without changing a published list. */
sealed interface MangaReaderItem {
    val chapterIndex: Int
    val pageIndex: Int

    data class Page(
        override val chapterIndex: Int,
        override val pageIndex: Int,
        val chapterCount: Int,
        val imageCount: Int,
        val chapterName: String,
        val imageUrl: String,
    ) : MangaReaderItem

    data class Boundary(
        override val chapterIndex: Int,
        override val pageIndex: Int,
        val message: String?,
        val volume: Boolean,
    ) : MangaReaderItem
}

internal fun snapshotMangaItems(items: List<Any>): List<MangaReaderItem> = items.map { item ->
    when (item) {
        is MangaPage ->
            MangaReaderItem.Page(
                chapterIndex = item.chapterIndex,
                pageIndex = item.index,
                chapterCount = item.chapterSize,
                imageCount = item.imageCount,
                chapterName = item.mChapterName,
                imageUrl = item.mImageUrl,
            )
        is ReaderLoading ->
            MangaReaderItem.Boundary(
                chapterIndex = item.chapterIndex,
                pageIndex = item.index,
                message = item.mMessage,
                volume = item.isVolume,
            )
        else -> error("Unknown manga item: ${item::class.java.name}")
    }
}

/** Boundary index follows the previous image, exactly as the legacy pre-scroll callback. */
internal fun MangaReaderItem.readingPage(): Int =
    when (this) {
        is MangaReaderItem.Page -> pageIndex
        is MangaReaderItem.Boundary -> (pageIndex - 1).coerceAtLeast(0)
    }

internal enum class MangaTapAction {
    Menu,
    Previous,
    Next,
    None,
}

/** The old reader has three tap rectangles, rather than entire left/right half-screen targets. */
internal fun mangaTapAction(
    x: Float,
    y: Float,
    rightToLeft: Boolean,
    disableClickScroll: Boolean,
): MangaTapAction =
    when {
        x >= .33f && x < .66f && y >= .33f && y < .66f -> MangaTapAction.Menu
        disableClickScroll -> MangaTapAction.None
        x >= 0f && x < .33f && y >= .66f && y < 1f -> {
            if (rightToLeft) MangaTapAction.Next else MangaTapAction.Previous
        }
        x >= .66f && x < 1f && y >= .66f && y < 1f -> {
            if (rightToLeft) MangaTapAction.Previous else MangaTapAction.Next
        }
        else -> MangaTapAction.None
    }
