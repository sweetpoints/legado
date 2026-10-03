package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.removeSortCache
import io.legado.app.help.source.sortUrls
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Nullable search distinguishes a category browser from even an empty submitted search. */
data class RssCategoryRequest(
    val sourceUrl: String? = null,
    val sortUrl: String? = null,
    val query: String? = null,
)

data class RssCategoryTab(val index: Int, val name: String, val url: String)

data class RssCategorySnapshot(val source: RssSource?, val tabs: List<RssCategoryTab>)

data class RssCategoryVariable(val key: String, val value: String?, val comment: String)

interface RssCategoryRepository {
    suspend fun load(request: RssCategoryRequest, refresh: Boolean = false): RssCategorySnapshot

    suspend fun switchStyle(sourceUrl: String): RssSource

    suspend fun clearArticles(sourceUrl: String)

    suspend fun variable(sourceUrl: String): RssCategoryVariable

    suspend fun variable(sourceUrl: String, value: String?)
}

/** Existing sort scripts, cache and variable storage remain the authoritative RSS pipeline. */
class AppRssCategoryRepository(
    private val database: AppDatabase = appDb,
    private val categories: suspend (RssSource) -> List<Pair<String, String>> = { it.sortUrls() },
    private val invalidate: suspend (RssSource) -> Unit = { it.removeSortCache() },
) : RssCategoryRepository {
    override suspend fun load(request: RssCategoryRequest, refresh: Boolean): RssCategorySnapshot =
        withContext(Dispatchers.IO) {
            val source =
                request.sourceUrl?.let {
                    database.rssSourceDao.getByKey(it)?.copy() ?: RssSource(sourceUrl = it)
                }
            if (source == null) return@withContext RssCategorySnapshot(null, emptyList())
            if (refresh) invalidate(source)
            val tabs =
                when {
                    request.query != null -> source.searchUrl?.let { listOf("搜索" to it) }.orEmpty()
                    !request.sortUrl.isNullOrBlank() -> directRssCategoryUrls(request.sortUrl)
                    else -> categories(source)
                }
            RssCategorySnapshot(
                source.copy(),
                tabs.mapIndexed { index, entry -> RssCategoryTab(index, entry.first, entry.second) },
            )
        }

    override suspend fun switchStyle(sourceUrl: String): RssSource =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val source =
                    database.rssSourceDao.getByKey(sourceUrl)?.copy()
                        ?: RssSource(sourceUrl = sourceUrl)
                source.articleStyle = if (source.articleStyle < 4) source.articleStyle + 1 else 0
                database.rssSourceDao.update(source)
                source.copy()
            }
        }

    override suspend fun clearArticles(sourceUrl: String): Unit =
        withContext(Dispatchers.IO) { database.rssArticleDao.delete(sourceUrl) }

    override suspend fun variable(sourceUrl: String): RssCategoryVariable =
        withContext(Dispatchers.IO) {
            val source =
                database.rssSourceDao.getByKey(sourceUrl)?.copy()
                    ?: RssSource(sourceUrl = sourceUrl)
            RssCategoryVariable(
                source.getKey(),
                source.getVariable(),
                source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取"),
            )
        }

    override suspend fun variable(sourceUrl: String, value: String?): Unit =
        withContext(Dispatchers.IO) {
            (database.rssSourceDao.getByKey(sourceUrl)?.copy() ?: RssSource(sourceUrl = sourceUrl))
                .setVariable(value)
        }
}

/**
 * Explicit caller URLs accept ordered JSON maps and fall back to the original string on malformed
 * input.
 */
internal fun directRssCategoryUrls(url: String): List<Pair<String, String>> =
    try {
        if (url.isJsonObject())
            GSONStrict.fromJsonObject<Map<String, String>>(url).getOrThrow().map {
                it.key to it.value
            }
        else listOf("" to url)
    } catch (_: Exception) {
        listOf("" to url)
    }
