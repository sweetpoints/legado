package io.legado.app.data.repository

import io.legado.app.data.entities.BookSourcePart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Only identity and display names cross the data/UI boundary. */
internal data class SearchScopeSource(val url: String, val name: String)

internal interface SearchScopeStore {
    suspend fun enabledGroups(): List<String>

    fun sources(query: String): Flow<List<BookSourcePart>>
}

internal interface SearchScopeRepository {
    suspend fun groups(): List<String>

    fun sources(query: String): Flow<List<SearchScopeSource>>
}

internal class DefaultSearchScopeRepository(
    private val store: SearchScopeStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SearchScopeRepository {
    override suspend fun groups(): List<String> = withContext(io) { store.enabledGroups().toList() }

    override fun sources(query: String): Flow<List<SearchScopeSource>> =
        store
            .sources(query)
            .map { rows -> rows.map { SearchScopeSource(it.bookSourceUrl, it.bookSourceName) } }
            .flowOn(io)
}
