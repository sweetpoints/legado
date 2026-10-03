package io.legado.app.ui.main.explore

import androidx.appcompat.app.AppCompatActivity
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.ExploreInfoMapStore.exploreInfoMapList
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.InfoMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface ExploreHomeRepository {
    fun sources(query: String): Flow<List<ExploreHomeSource>>

    fun groups(): Flow<List<String>>

    suspend fun panel(url: String, refresh: Boolean): ExploreHomePanel

    suspend fun execute(
        url: String,
        controlId: Int,
        values: Map<String, String>,
        activity: AppCompatActivity?,
        callback: SourceLoginJsExtensions.Callback,
    )

    suspend fun saveValues(url: String, values: Map<String, String>)

    suspend fun savePendingValues()

    suspend fun top(url: String)

    suspend fun delete(url: String)

    suspend fun searchSource(url: String): BookSourcePart?

    suspend fun eInkMode(): Boolean

    suspend fun showFastScroller(): Boolean
}

internal class AppExploreHomeRepository : ExploreHomeRepository {
    private val kinds = mutableMapOf<String, List<ExploreKind>>()
    private val scriptMutex = Mutex()

    override fun sources(query: String): Flow<List<ExploreHomeSource>> {
        val dao = appDb.bookSourceDao
        val group = exploreGroupFromQuery(query)
        val sources =
            when {
                query.isBlank() -> dao.flowExplore()
                group != null -> dao.flowGroupExplore(group)
                else -> dao.flowExplore(query)
            }
        return sources
            .map { rows ->
                rows.map { ExploreHomeSource(it.bookSourceUrl, it.bookSourceName, it.hasLoginUrl) }
            }
            .flowOn(Dispatchers.IO)
    }

    override fun groups(): Flow<List<String>> =
        appDb.bookSourceDao.flowExploreGroups().map { it.toList() }.flowOn(Dispatchers.IO)

    override suspend fun showFastScroller(): Boolean =
        withContext(Dispatchers.IO) { AppConfig.showDiscoveryFastScroller }

    override suspend fun eInkMode(): Boolean = withContext(Dispatchers.IO) { AppConfig.isEInkMode }

    private fun infoMap(url: String): InfoMap =
        exploreInfoMapList[url] ?: InfoMap(url).also { exploreInfoMapList.put(url, it) }

    override suspend fun panel(url: String, refresh: Boolean): ExploreHomePanel =
        withContext(Dispatchers.IO) {
            scriptMutex.withLock {
                val source = appDb.bookSourceDao.getBookSource(url) ?: error("书源不存在")
                if (refresh) source.clearExploreKindsCache()
                val loadedKinds = source.exploreKinds()
                kinds[url] = loadedKinds
                val values = infoMap(url)
                ExploreHomePanel(
                    loadedKinds.mapIndexed { index, kind ->
                        val choices =
                            kind.chars?.filterNotNull()?.takeIf { it.isNotEmpty() }
                                ?: listOf("chars", "is null")
                        val value =
                            if (kind.type == "toggle" || kind.type == "select") {
                                values[kind.title]?.takeIf { it.isNotEmpty() }
                                    ?: (kind.default ?: choices.first()).also {
                                        values[kind.title] = it
                                    }
                            } else values[kind.title].orEmpty()
                        val label =
                            when {
                                kind.viewName == null -> kind.title
                                kind.viewName!!.length in 3..19 &&
                                    kind.viewName!!.startsWith("'") &&
                                    kind.viewName!!.endsWith("'") ->
                                    kind.viewName!!.drop(1).dropLast(1)
                                else ->
                                    try {
                                        runScriptWithContext {
                                            source
                                                .evalJS(kind.viewName!!) {
                                                    put("infoMap", values)
                                                }
                                                .toString()
                                        }
                                            .takeIf { it.isNotEmpty() } ?: "null"
                                    } catch (failure: Exception) {
                                        AppLog.put("${source.getTag()} exploreUi err", failure)
                                        "null"
                                    }
                            }
                        val style = kind.style()
                        ExploreHomeControl(
                            index,
                            kind.type,
                            kind.title,
                            label,
                            kind.url,
                            choices.toList(),
                            value,
                            ExploreControlStyle(
                                style.layout_flexGrow,
                                style.layout_flexShrink,
                                style.layout_flexBasisPercent,
                                style.layout_alignSelf,
                                style.layout_justifySelf,
                                style.layout_wrapBefore,
                            ),
                        )
                    }
                )
            }
        }

    override suspend fun saveValues(url: String, values: Map<String, String>) =
        withContext(Dispatchers.IO) {
            scriptMutex.withLock { infoMap(url).putAll(values) }
        }

    override suspend fun execute(
        url: String,
        controlId: Int,
        values: Map<String, String>,
        activity: AppCompatActivity?,
        callback: SourceLoginJsExtensions.Callback,
    ) =
        withContext(Dispatchers.IO) {
            scriptMutex.withLock {
                val source = appDb.bookSourceDao.getBookSource(url) ?: return@withLock
                val kind = kinds[url]?.getOrNull(controlId) ?: return@withLock
                val action = kind.action?.takeIf { it.isNotBlank() } ?: return@withLock
                val info = infoMap(url).apply { putAll(values) }
                val bridge = SourceLoginJsExtensions(activity, source, callback = callback)
                try {
                    runScriptWithContext {
                        source.evalJS(action) {
                            put("java", bridge)
                            put("infoMap", info)
                        }
                    }
                } catch (failure: Exception) {
                    AppLog.put("ExploreUI Button ${kind.title} JavaScript error", failure)
                    throw failure
                }
            }
        }

    override suspend fun savePendingValues() =
        withContext(Dispatchers.IO) {
            scriptMutex.withLock {
                exploreInfoMapList.snapshot().values.filter { it.needSave }.forEach { it.saveNow() }
            }
        }

    override suspend fun top(url: String) =
        withContext(Dispatchers.IO) {
            appDb.runInTransaction {
                val source = appDb.bookSourceDao.getBookSourcePart(url) ?: return@runInTransaction
                appDb.bookSourceDao.upOrder(
                    source.copy(customOrder = appDb.bookSourceDao.minOrder - 1)
                )
            }
        }

    override suspend fun delete(url: String) =
        withContext(Dispatchers.IO) { SourceHelp.deleteBookSource(url) }

    override suspend fun searchSource(url: String): BookSourcePart? =
        withContext(Dispatchers.IO) { appDb.bookSourceDao.getBookSourcePart(url) }
}
