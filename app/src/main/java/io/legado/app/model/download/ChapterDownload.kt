package io.legado.app.model.download

import io.legado.app.help.audio.AudioCachePolicy

enum class ChapterDownloadMode {
    Book,
    Audio,
}

data class ChapterDownloadRange(val start: Int, val endInclusive: Int)

data class ChapterDownloadSelection(val token: String, val range: ChapterDownloadRange)

/** Arbitrary book fields stay in a private session; only five-digit input belongs in SavedState. */
data class ChapterDownloadSession(
    val bookJson: String,
    val mode: ChapterDownloadMode,
    val initialChapter: Int,
    val chapterCount: Int,
    val revision: Long = 0,
    val pending: ChapterDownloadSelection? = null,
    val completed: Boolean = false,
)

fun chapterDownloadRange(
    mode: ChapterDownloadMode,
    start: String,
    end: String,
    chapterCount: Int,
): ChapterDownloadRange? {
    val first =
        start.toIntOrNull()
            ?: if (mode == ChapterDownloadMode.Book && start.isEmpty()) 0 else return null
    val last =
        end.toIntOrNull()
            ?: if (mode == ChapterDownloadMode.Book && end.isEmpty()) chapterCount else return null
    if (mode == ChapterDownloadMode.Book) return ChapterDownloadRange(first - 1, last - 1)
    return AudioCachePolicy.normalizeRange(first - 1, last - 1, chapterCount)?.let {
        ChapterDownloadRange(it.first, it.last)
    }
}
