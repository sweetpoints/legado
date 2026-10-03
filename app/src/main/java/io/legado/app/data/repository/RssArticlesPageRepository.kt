package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.model.rss.Rss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class RssArticlesParameters(
    val sourceUrl: String,
    val sortName: String,
    val sortUrl: String,
    val query: String? = null,
    val preload: Boolean = false,
    val contentRevision: Long = 0,
)

data class RssArticleRow(
    val key: String,
    val title: String,
    val image: String?,
    val pubDate: String?,
    val read: Boolean,
    val origin: String,
)

data class RssArticlesBatch(
    val articles: List<RssArticle>,
    val nextUrl: String?,
    val clearOld: Boolean,
)

data class RssArticlesCommit(val order: Long, val added: Boolean)

interface RssArticlesPageRepository {
    fun observe(parameters: RssArticlesParameters): Flow<List<RssArticleRow>>

    suspend fun fetch(parameters: RssArticlesParameters, url: String, page: Int): RssArticlesBatch

    suspend fun refresh(
        parameters: RssArticlesParameters,
        batch: RssArticlesBatch,
        order: Long,
    ): RssArticlesCommit

    suspend fun append(
        parameters: RssArticlesParameters,
        batch: RssArticlesBatch,
        order: Long,
    ): RssArticlesCommit

    suspend fun resolve(parameters: RssArticlesParameters, key: String): RssArticle?
}

/**
 * Room cache semantics and the established await parser are shared with the existing RSS pipeline.
 */
class AppRssArticlesPageRepository(
    private val database: AppDatabase = appDb,
    private val parser:
        suspend (String, String, RssSource, Int, String?) -> Pair<
                MutableList<RssArticle>,
                String?,
            > =
        Rss::getArticlesAwait,
) : RssArticlesPageRepository {
    override fun observe(parameters: RssArticlesParameters): Flow<List<RssArticleRow>> =
        database.rssArticleDao
            .flowByOriginSort(parameters.sourceUrl, parameters.sortName)
            .map { rows ->
                rows.map { article ->
                    RssArticleRow(
                        rssArticleRowKey(article),
                        article.title,
                        article.image,
                        article.pubDate,
                        article.read,
                        article.origin,
                    )
                }
            }
            .flowOn(Dispatchers.IO)

    override suspend fun fetch(
        parameters: RssArticlesParameters,
        url: String,
        page: Int,
    ): RssArticlesBatch =
        withContext(Dispatchers.IO) {
            val source =
                database.rssSourceDao.getByKey(parameters.sourceUrl)?.copy()
                    ?: RssSource(sourceUrl = parameters.sourceUrl)
            val value = parser(parameters.sortName, url, source, page, parameters.query)
            currentCoroutineContext().ensureActive()
            RssArticlesBatch(
                value.first.map { it.copy() },
                value.second,
                !source.ruleNextPage.isNullOrEmpty(),
            )
        }

    override suspend fun refresh(
        parameters: RssArticlesParameters,
        batch: RssArticlesBatch,
        order: Long,
    ): RssArticlesCommit =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                var nextOrder = order
                val articles = batch.articles.map { it.copy(order = nextOrder--) }
                database.rssArticleDao.insert(*articles.toTypedArray())
                if (batch.clearOld)
                    database.rssArticleDao.clearOld(
                        parameters.sourceUrl,
                        parameters.sortName,
                        nextOrder,
                    )
                RssArticlesCommit(nextOrder, articles.isNotEmpty())
            }
        }

    override suspend fun append(
        parameters: RssArticlesParameters,
        batch: RssArticlesBatch,
        order: Long,
    ): RssArticlesCommit =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                if (batch.articles.isEmpty()) return@withTransaction RssArticlesCommit(order, false)
                val first = batch.articles.first()
                val last = batch.articles.last()
                val firstStored = database.rssArticleDao.get(first.origin, first.link, first.sort)
                val lastStored = database.rssArticleDao.get(last.origin, last.link, first.sort)
                if (firstStored != null && lastStored != null)
                    return@withTransaction RssArticlesCommit(order, false)
                var nextOrder = order
                val articles = batch.articles.map { it.copy(order = nextOrder--) }
                database.rssArticleDao.append(*articles.toTypedArray())
                RssArticlesCommit(nextOrder, true)
            }
        }

    override suspend fun resolve(parameters: RssArticlesParameters, key: String): RssArticle? =
        withContext(Dispatchers.IO) {
            database.rssArticleDao
                .flowByOriginSort(parameters.sourceUrl, parameters.sortName)
                .first()
                .find { rssArticleRowKey(it) == key }
                ?.copy()
        }
}

/** The table has a three-column primary key; length boundaries avoid ambiguous concatenations. */
fun rssArticleRowKey(article: RssArticle): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(
            "${article.origin.length}:${article.origin}${article.link.length}:${article.link}${article.sort.length}:${article.sort}"
                .toByteArray()
        )
        .joinToString("") { "%02x".format(it) }
