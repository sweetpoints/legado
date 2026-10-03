package io.legado.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.BookshelfReadProgressMode
import io.legado.app.utils.cnCompare
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getBooleanCompat
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class BookshelfPageSettings(
    val layout: Int = 0,
    val marginPx: Int = 12,
    val gridTitle: Int = 0,
    val showUnread: Boolean = true,
    val readProgressMode: Int = 1,
    val showLatestUpdate: Boolean = false,
    val fastScroller: Boolean = false,
    val eInk: Boolean = false,
)

interface BookshelfPageRepository {
    fun books(groupId: Long): Flow<List<Book>>

    fun settings(): Flow<BookshelfPageSettings>
}

class RoomBookshelfPageRepository(context: Context) : BookshelfPageRepository {
    private val preferences = context.applicationContext.defaultSharedPreferences

    override fun books(groupId: Long): Flow<List<Book>> =
        appDb.bookDao
            .flowByGroup(groupId)
            .map { books -> books.map { it.copy() } }
            .flowOn(Dispatchers.IO)

    override fun settings(): Flow<BookshelfPageSettings> = callbackFlow {
        fun emitSettings() {
            trySend(
                BookshelfPageSettings(
                    layout = preferences.getInt(PreferKey.bookshelfLayout, 0).coerceIn(0, 6),
                    marginPx = preferences.getInt(PreferKey.bookshelfMargin, 12).coerceAtLeast(0),
                    gridTitle = preferences.getInt(PreferKey.showBooknameLayout, 0).coerceIn(0, 2),
                    showUnread = preferences.getBooleanCompat(PreferKey.showUnread, true),
                    readProgressMode =
                        BookshelfReadProgressMode.resolve(
                            preferences.all[PreferKey.bookshelfReadProgressMode],
                            preferences.all[PreferKey.showBookshelfReadProgress],
                        ),
                    showLatestUpdate =
                        preferences.getBooleanCompat(PreferKey.showLastUpdateTime, false),
                    fastScroller =
                        preferences.getBooleanCompat(PreferKey.showBookshelfFastScroller, false),
                    eInk = preferences.getString(PreferKey.themeMode, null) == "3",
                )
            )
        }
        val keys =
            setOf(
                PreferKey.bookshelfLayout,
                PreferKey.bookshelfMargin,
                PreferKey.showBooknameLayout,
                PreferKey.showUnread,
                PreferKey.bookshelfReadProgressMode,
                PreferKey.showBookshelfReadProgress,
                PreferKey.showLastUpdateTime,
                PreferKey.showBookshelfFastScroller,
                PreferKey.themeMode,
            )
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) emitSettings()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        emitSettings()
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .conflate()
        .flowOn(Dispatchers.IO)
}

internal fun sortBookshelfPageBooks(books: List<Book>, sort: Int): List<Book> =
    when (sort) {
        1 -> books.sortedByDescending { it.latestChapterTime }
        2 -> books.sortedWith { first, second -> first.name.cnCompare(second.name) }
        3 -> books.sortedBy { it.order }
        4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
        5 -> books.sortedWith { first, second -> first.author.cnCompare(second.author) }
        else -> books.sortedByDescending { it.durChapterTime }
    }
