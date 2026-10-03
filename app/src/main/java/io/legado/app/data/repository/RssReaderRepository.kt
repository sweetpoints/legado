package io.legado.app.data.repository

import androidx.room.withTransaction
import com.script.rhino.runScriptWithContext
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.webView.toWebViewRequestConfig
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.rss.Rss
import io.legado.app.model.rss.mergeRssReaderVariables
import io.legado.app.model.rss.rssReaderHtml
import io.legado.app.model.rss.rssReaderStartHtml
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.*

/**
 * Full navigation and HTML input live in a private session, never an Activity default SavedState
 * value.
 */
data class RssReaderRequest(
    val origin: String? = null,
    val title: String? = null,
    val link: String? = null,
    val sort: String? = null,
    val openUrl: String? = null,
    val startHtml: String? = null,
)

sealed interface RssReaderDocument {
    data class Url(val url: String, val headers: Map<String, String>, val userAgent: String?) :
        RssReaderDocument

    data class Html(val html: String, val baseUrl: String?, val historyUrl: String) :
        RssReaderDocument
}

data class RssReaderSnapshot(
    val request: RssReaderRequest,
    val title: String,
    val source: RssSource?,
    val article: RssArticle?,
    val favorite: RssStar?,
    val headers: Map<String, String>,
    val document: RssReaderDocument?,
)

interface RssReaderRepository {
    suspend fun load(request: RssReaderRequest): RssReaderSnapshot?

    suspend fun addFavorite(article: RssArticle): RssStar

    suspend fun updateFavorite(
        article: RssArticle,
        title: String?,
        group: String?,
    ): Pair<RssArticle, RssStar>

    suspend fun deleteFavorite(origin: String, link: String)
}

/** The parser, header scripts, and Room metadata remain the same established RSS pipeline. */
class AppRssReaderRepository(
    private val database: AppDatabase = appDb,
    private val content: suspend (RssArticle, String, RssSource) -> String = Rss::getContentAwait,
) : RssReaderRepository {
    override suspend fun load(request: RssReaderRequest): RssReaderSnapshot? =
        withContext(Dispatchers.IO) {
            val origin = request.origin ?: return@withContext null
            val source = database.rssSourceDao.getByKey(origin)?.copy()
            val headers = runScriptWithContext { source?.getHeaderMap() ?: emptyMap() }.toMap()
            val title = request.title ?: source?.sourceName ?: origin
            val favorite = request.link?.let { database.rssStarDao.get(origin, it)?.copy() }
            var article =
                request.link?.let { link ->
                    favorite?.toRssArticle()
                        ?: (if (request.sort == null) database.rssArticleDao.getByLink(origin, link)
                            else database.rssArticleDao.get(origin, link, request.sort))
                            ?.copy()
                }
            var currentFavorite = favorite
            val preload = !source?.preloadJs.isNullOrBlank()
            suspend fun url(value: String?, base: String): RssReaderDocument.Url {
                val analyzed =
                    AnalyzeUrl(
                        mUrl = value ?: base,
                        baseUrl = base,
                        source = source,
                        coroutineContext = currentCoroutineContext(),
                        hasLoginHeader = false,
                    )
                val config = analyzed.headerMap.toWebViewRequestConfig(analyzed.getUserAgent())
                return RssReaderDocument.Url(
                    analyzed.url,
                    config.additionalHeaders.toMap(),
                    config.userAgent,
                )
            }
            suspend fun rule(value: RssArticle, expression: String): RssReaderDocument? {
                val owner = source ?: return null
                val parsedArticle = value.copy()
                val baselineVariables = value.variable
                val body =
                    try {
                        content(parsedArticle, expression, owner.copy())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        return html(value, "加载正文失败\n${error.stackTraceToString()}", owner)
                    }
                currentCoroutineContext().ensureActive()
                val parsed = database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    val latest =
                        database.rssArticleDao.get(value.origin, value.link, value.sort)?.copy()
                            ?: value.copy()
                    val updated =
                        latest.copy(
                            description = body,
                            variable =
                                mergeRssReaderVariables(
                                    baselineVariables,
                                    parsedArticle.variable,
                                    latest.variable,
                                ),
                        )
                    database.rssArticleDao.insert(updated)
                    currentFavorite =
                        database.rssStarDao.get(value.origin, value.link)?.copy()?.let { stored ->
                            stored.copy(description = body).also { database.rssStarDao.insert(it) }
                        }
                    updated
                }
                article = parsed
                return html(parsed, body, owner)
            }
            val document =
                if (request.link != null) {
                    val current = article
                    when {
                        current == null -> url(request.link, origin)
                        !current.description.isNullOrBlank() ->
                            html(current, current.description!!, source)
                        !source?.ruleContent.isNullOrBlank() ->
                            rule(current, source!!.ruleContent!!)
                        else -> url(current.link, current.origin)
                    }
                } else
                    when {
                        request.startHtml != null ->
                            source?.let {
                                RssReaderDocument.Html(
                                    rssReaderStartHtml(
                                        request.startHtml,
                                        it.startJs,
                                        it.startStyle ?: it.style,
                                        preload,
                                    ),
                                    if (it.loadWithBaseUrl) it.sourceUrl else null,
                                    it.sourceUrl,
                                )
                            }
                        source?.ruleContent.isNullOrBlank() || source?.singleUrl == true ->
                            url(request.openUrl, origin)
                        request.openUrl != null -> {
                            val current =
                                database.rssArticleDao.getByLink(origin, request.openUrl)?.copy()
                                    ?: RssArticle(
                                        origin = origin,
                                        sort = title,
                                        title = title,
                                        link = request.openUrl,
                                    )
                            rule(current, source!!.ruleContent!!)
                        }
                        else -> null
                    }
            currentCoroutineContext().ensureActive()
            RssReaderSnapshot(
                request,
                title,
                source?.copy(),
                article?.copy(),
                currentFavorite?.copy(),
                headers,
                document,
            )
        }

    private fun html(
        article: RssArticle,
        body: String,
        source: RssSource?,
    ): RssReaderDocument.Html {
        val url = NetworkUtils.getAbsoluteURL(article.origin, article.link).substringBefore("@js")
        return RssReaderDocument.Html(
            rssReaderHtml(body, source?.style, !source?.preloadJs.isNullOrBlank()),
            if (source?.loadWithBaseUrl == false) null else url,
            url,
        )
    }

    override suspend fun addFavorite(article: RssArticle): RssStar =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                database.rssStarDao.get(article.origin, article.link)?.copy()
                    ?: article.copy().toStar().also { database.rssStarDao.insert(it) }
            }
        }

    override suspend fun updateFavorite(
        article: RssArticle,
        title: String?,
        group: String?,
    ): Pair<RssArticle, RssStar> =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                val current =
                    database.rssStarDao.get(article.origin, article.link)?.toRssArticle()
                        ?: article.copy()
                val updated =
                    current.copy(title = title ?: current.title, group = group ?: current.group)
                val favorite = updated.toStar()
                database.rssStarDao.update(favorite)
                updated to favorite
            }
        }

    override suspend fun deleteFavorite(origin: String, link: String) =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            database.rssStarDao.delete(origin, link)
        }
}
