package io.legado.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.asFlow
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getBooleanCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

data class BookshelfHomePreferences(
    val selectedPosition: Int = 0,
    val sort: Int = 0,
    val recent: Boolean = false,
    val stats: Boolean = false,
)

data class BookshelfHomeHeader(
    val bookCount: Int = 0,
    val readingCount: Int = 0,
    val recent: Book? = null,
)

interface BookshelfHomeRepository {
    fun groups(): Flow<List<BookGroup>>

    fun preferences(): Flow<BookshelfHomePreferences>

    fun header(preferences: BookshelfHomePreferences): Flow<BookshelfHomeHeader>

    fun select(position: Int)

    suspend fun enableAll()

    suspend fun book(key: String): Book?

    suspend fun group(id: Long): BookGroup?
}

class RoomBookshelfHomeRepository(context: Context) : BookshelfHomeRepository {
    private val prefs = context.applicationContext.defaultSharedPreferences

    // Preserve the DAO's existing visible-group query, including special groups.
    override fun groups(): Flow<List<BookGroup>> =
        appDb.bookGroupDao.show.asFlow().map { groups -> groups.map { it.copy() } }

    override fun preferences(): Flow<BookshelfHomePreferences> = callbackFlow {
        fun emit() {
            trySend(
                BookshelfHomePreferences(
                    prefs.getInt(PreferKey.saveTabPosition, 0),
                    prefs.getInt(PreferKey.bookshelfSort, 0),
                    prefs.getBooleanCompat(PreferKey.showBookshelfRecentReading, false),
                    prefs.getBooleanCompat(PreferKey.showBookshelfStats, false),
                )
            )
        }
        val keys =
            setOf(
                PreferKey.saveTabPosition,
                PreferKey.bookshelfSort,
                PreferKey.showBookshelfRecentReading,
                PreferKey.showBookshelfStats,
            )
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) emit()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        emit()
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        .conflate()

    override fun header(preferences: BookshelfHomePreferences): Flow<BookshelfHomeHeader> =
        if (!preferences.recent && !preferences.stats) flowOf(BookshelfHomeHeader())
        else
            appDb.bookDao
                .flowShelfBookCount()
                .map { count ->
                    BookshelfHomeHeader(
                        count,
                        if (preferences.stats) appDb.bookDao.readingCount else 0,
                        if (preferences.recent) appDb.bookDao.lastReadBookOnShelf?.copy() else null,
                    )
                }
                .flowOn(Dispatchers.IO)

    override fun select(position: Int) {
        prefs.edit().putInt(PreferKey.saveTabPosition, position).apply()
    }

    override suspend fun enableAll() =
        withContext(Dispatchers.IO) { appDb.bookGroupDao.enableGroup(BookGroup.IdAll) }

    override suspend fun book(key: String) =
        withContext(Dispatchers.IO) { appDb.bookDao.getBook(key)?.copy() }

    override suspend fun group(id: Long) =
        withContext(Dispatchers.IO) { appDb.bookGroupDao.getByID(id)?.copy() }
}
