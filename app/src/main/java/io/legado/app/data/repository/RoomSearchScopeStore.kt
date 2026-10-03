package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb

internal class RoomSearchScopeStore(private val database: AppDatabase = appDb) : SearchScopeStore {
    override suspend fun enabledGroups() = database.bookSourceDao.allEnabledGroups()

    // The original source tab includes disabled sources and searches names, groups, URLs and
    // comments.
    override fun sources(query: String) =
        if (query.isEmpty()) database.bookSourceDao.flowAll()
        else database.bookSourceDao.flowSearch(query)
}
