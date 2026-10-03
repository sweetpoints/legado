package io.legado.app.data.preferences

import io.legado.app.model.webBook.BookSearchPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface BookSearchPreferencesStore {
    fun changes(): Flow<Unit>

    suspend fun load(): BookSearchPreferences

    suspend fun precision(value: Boolean)

    suspend fun showReadRecord(value: Boolean)

    suspend fun resultFilter(value: String)

    suspend fun scope(value: String)
}

internal interface BookSearchPreferencesRepository {
    fun observe(): Flow<BookSearchPreferences>

    suspend fun load(): BookSearchPreferences

    suspend fun precision(value: Boolean): BookSearchPreferences

    suspend fun showReadRecord(value: Boolean): BookSearchPreferences

    suspend fun resultFilter(value: String): BookSearchPreferences

    suspend fun scope(value: String): BookSearchPreferences
}

internal class DefaultBookSearchPreferencesRepository(
    private val store: BookSearchPreferencesStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookSearchPreferencesRepository {
    override fun observe(): Flow<BookSearchPreferences> {
        return store.changes().map { store.load() }.distinctUntilChanged().flowOn(io)
    }

    override suspend fun load(): BookSearchPreferences = withContext(io) { store.load() }

    override suspend fun precision(value: Boolean): BookSearchPreferences {
        return accepted { store.precision(value) }
    }

    override suspend fun showReadRecord(value: Boolean): BookSearchPreferences {
        return accepted { store.showReadRecord(value) }
    }

    override suspend fun resultFilter(value: String): BookSearchPreferences {
        return accepted { store.resultFilter(value) }
    }

    override suspend fun scope(value: String): BookSearchPreferences {
        return accepted { store.scope(value) }
    }

    private suspend fun accepted(write: suspend () -> Unit): BookSearchPreferences {
        return writes.withLock {
            // Once a preference write starts, retain its accepted snapshot across caller
            // cancellation.
            withContext(io + NonCancellable) {
                write()
                store.load()
            }
        }
    }

    private companion object {
        val writes = Mutex()
    }
}
