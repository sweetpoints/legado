package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Full entities are handed to the established reader only after IO-owned preparation. */
data class RssArticlesRead(val article: RssArticle, val source: RssSource?)

fun interface RssArticlesReadRepository {
    suspend fun prepare(parameters: RssArticlesParameters, key: String): RssArticlesRead?
}

class AppRssArticlesReadRepository(private val database: AppDatabase = appDb) :
    RssArticlesReadRepository {
    override suspend fun prepare(parameters: RssArticlesParameters, key: String): RssArticlesRead? =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val article =
                    database.rssArticleDao
                        .flowByOriginSort(parameters.sourceUrl, parameters.sortName)
                        .first()
                        .find { rssArticleRowKey(it) == key }
                        ?.copy() ?: return@withTransaction null
                val source = database.rssSourceDao.getByKey(article.origin)?.copy()
                currentCoroutineContext().ensureActive()
                // IGNORE preserves the first read timestamp when lifecycle cancellation causes a
                // retry.
                database.rssReadRecordDao.insertRecord(article.toRecord())
                RssArticlesRead(article, source)
            }
        }
}
