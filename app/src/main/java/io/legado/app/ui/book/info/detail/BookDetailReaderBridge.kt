package io.legado.app.ui.book.info.detail

import android.os.Looper
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookDetailMutationKind
import io.legado.app.data.repository.BookDetailNativeKind
import io.legado.app.data.repository.BookDetailNativePayload
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook

/**
 * Applies committed metadata without replacing positions/configuration that the active reader
 * changed meanwhile.
 */
internal fun mergeBookDetailLiveReaderBook(
    committed: Book,
    live: Book,
    mutation: BookDetailMutationKind? = null,
): Book =
    committed
        .copy(
            durChapterIndex = live.durChapterIndex,
            durChapterPos = live.durChapterPos,
            durChapterTitle = live.durChapterTitle,
            durVolumeIndex = live.durVolumeIndex,
            chapterInVolumeIndex = live.chapterInVolumeIndex,
            durChapterTime = live.durChapterTime,
            readConfig =
                if (mutation == BookDetailMutationKind.SplitLong)
                    (live.readConfig ?: Book.ReadConfig()).copy(
                        splitLongChapter = committed.readConfig?.splitLongChapter ?: true
                    )
                else live.readConfig,
            syncTime = live.syncTime,
        )
        .also {
            it.infoHtml = committed.infoHtml
            it.tocHtml = committed.tocHtml
            it.downloadUrls = committed.downloadUrls
        }

/** Called only after a resumed native claim. All Room/JSON preparation was completed on IO. */
internal fun applyBookDetailReaderPayload(payload: BookDetailNativePayload): Boolean {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Reader publication requires Main" }
    if (payload.effect.kind != BookDetailNativeKind.ReaderSync) return false
    val committed = payload.book ?: return false
    val expected = payload.effect.expectedBookUrl ?: committed.bookUrl
    ReadBook.book
        ?.takeIf { it.bookUrl == expected || it.bookUrl == committed.bookUrl }
        ?.let { live ->
            val merged = mergeBookDetailLiveReaderBook(committed, live, payload.effect.mutation)
            ReadBook.book = merged
            ReadBook.applyPreparedHighlights(merged.bookUrl, payload.highlights)
            if (payload.effect.flag) ReadBook.onChapterListUpdated(merged)
            return true
        }
    AudioPlay.book
        ?.takeIf { it.bookUrl == expected || it.bookUrl == committed.bookUrl }
        ?.let { live ->
            AudioPlay.book = mergeBookDetailLiveReaderBook(committed, live, payload.effect.mutation)
            return true
        }
    return false
}
