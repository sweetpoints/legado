package io.legado.app.data.repository

import androidx.room.withTransaction
import com.script.rhino.runScriptWithContext
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.SourceHelp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

/** Immutable card data; parser and Room entities never escape into a composable. */
data class MainRssRow(val id: String, val sourceUrl: String, val name: String, val icon: String?, val hasLogin: Boolean)
interface MainRssRepository {
    fun rows(query: String): Flow<List<MainRssRow>>
    fun groups(): Flow<List<String>>
    suspend fun source(id: String): RssSource?
    suspend fun top(id: String)
    suspend fun disable(id: String)
    suspend fun delete(id: String)
    suspend fun prepare(id: String): MainRssNavigation?
}
internal fun mainRssId(url: String): String = MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun mainRssRow(source: RssSource) = MainRssRow(mainRssId(source.sourceUrl), source.sourceUrl,
    source.sourceName, source.sourceIcon, !source.loginUrl.isNullOrBlank())

/** Preserve the established first-category/single-link and JS fallback grammar exactly. */
internal fun mainRssSingleUrl(source: RssSource, evaluate: (String) -> String?): String {
    var value = source.sortUrl?.takeUnless { it.isBlank() } ?: return source.sourceUrl
    if (value.startsWith("<js>") || value.startsWith("@js:")) {
        val script = if (value.startsWith("@")) value.substring(4) else value.substring(4, value.lastIndexOf("<"))
        evaluate(script)?.takeUnless { it.isBlank() }?.let { value = it }
    }
    return if (value.contains("::")) value.split("::")[1] else value
}
internal fun mainRssStartHtml(source: RssSource, evaluate: (String) -> String?): String? {
    val html = source.startHtml ?: return null
    return when {
        html.startsWith("@js:") -> evaluate(html.substring(4)).toString()
        html.startsWith("<js>") -> evaluate(html.substring(4, html.lastIndexOf("<"))).toString()
        else -> html
    }
}
internal fun mainRssNavigation(source: RssSource, evaluate: (String) -> String?): MainRssNavigation {
    if (source.singleUrl) {
        val url = mainRssSingleUrl(source, evaluate)
        return MainRssNavigation(if (url.startsWith("http", true)) MainRssDestination.ReaderLink else MainRssDestination.External,
            source.sourceUrl, source.sourceName, url)
    }
    val html = mainRssStartHtml(source, evaluate)
    return MainRssNavigation(if (html.isNullOrBlank()) MainRssDestination.Categories else MainRssDestination.ReaderHtml,
        source.sourceUrl, source.sourceName, html?.takeUnless { it.isBlank() })
}

/** Enabled-only feeds and exact group queries retain the DAO's established order. */
class AppMainRssRepository(private val database: AppDatabase = appDb,
    private val deleteSources: (List<RssSource>) -> Unit = SourceHelp::deleteRssSources,
    private val evaluate: (RssSource, String) -> String? = { source, script ->
        source.evalJS(script)?.toString()
    }) : MainRssRepository {
    private val dao get() = database.rssSourceDao
    override fun rows(query: String): Flow<List<MainRssRow>> = when {
        query.isEmpty() -> dao.flowEnabled()
        query.startsWith("group:") -> dao.flowEnabledByGroup(query.substringAfter("group:"))
        else -> dao.flowEnabled(query)
    }.map { it.map(::mainRssRow) }.flowOn(Dispatchers.IO)
    override fun groups() = dao.flowEnabledGroups().flowOn(Dispatchers.IO)
    private fun current(id: String) = dao.all.firstOrNull { mainRssId(it.sourceUrl) == id }
    override suspend fun source(id: String) = withContext(Dispatchers.IO) { current(id)?.copy() }
    override suspend fun top(id: String): Unit = withContext(Dispatchers.IO) {
        database.withTransaction { current(id)?.let { dao.update(it.copy(customOrder = dao.minOrder - 1)) } }
    }
    override suspend fun disable(id: String): Unit = withContext(Dispatchers.IO) {
        database.withTransaction { current(id)?.let { dao.enable(it.sourceUrl, false) } }
    }
    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) { current(id)?.let { deleteSources(listOf(it)) } }
    override suspend fun prepare(id: String): MainRssNavigation? = withTimeout(10_000) {
        withContext(Dispatchers.IO) {
            val source = current(id)?.copy() ?: return@withContext null
            val navigation = runScriptWithContext { mainRssNavigation(source) { script -> evaluate(source, script) } }
            currentCoroutineContext().ensureActive(); navigation
        }
    }
}
