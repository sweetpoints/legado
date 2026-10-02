package io.legado.app.ui.main.bookshelf.components

import io.legado.app.data.entities.Book
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.BookshelfReadProgressMode
import kotlin.math.roundToInt

enum class BookshelfCardLayout { List, Compact, Grid }
enum class BookshelfGridTitle { Below, Hidden, Overlay }
data class BookshelfBookCardModel(
    val key: String,
    val name: String,
    val author: String,
    val currentChapter: String,
    val latestChapter: String,
    val unreadCount: Int = 0,
    val highlightUnread: Boolean = false,
    val updating: Boolean = false,
    val readProgress: Float? = null,
    val progressThicknessDp: Int = 2,
    val latestUpdateLabel: String? = null,
) {
    val progressPercent: String? get() = readProgress?.let { "${(it.coerceIn(0f, 1f) * 100).roundToInt()}%" }
}
data class BookshelfGroupCardModel(val key: Long, val name: String)
data class BookshelfHeaderModel(val stats: Pair<Int, Int>? = null, val recent: BookshelfBookCardModel? = null)

/** Build a value snapshot outside composition; never retain mutable Room Book objects in Screen. */
internal fun Book.toBookshelfCardModel(showUnread: Boolean, readProgressMode: Int,
    updating: Boolean, latestUpdateLabel: String? = null): BookshelfBookCardModel = BookshelfBookCardModel(
    key = bookUrl, name = name, author = author, currentChapter = durChapterTitle.orEmpty(),
    latestChapter = latestChapterTitle.orEmpty(), unreadCount = if (showUnread) getUnreadChapterNum() else 0,
    highlightUnread = lastCheckCount > 0, updating = !isLocal && updating,
    readProgress = readProgress().takeIf { BookshelfReadProgressMode.normalize(readProgressMode) != BookshelfReadProgressMode.HIDDEN },
    progressThicknessDp = BookshelfReadProgressMode.thicknessDp(readProgressMode),
    latestUpdateLabel = latestUpdateLabel.takeUnless { isLocal },
)
