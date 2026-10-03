package io.legado.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.DefaultData
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.splitNotBlank
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface TxtTocRuleManagementRepository {
    fun observe(): Flow<List<TxtTocRuleSnapshot>>

    suspend fun setEnabled(ids: List<Long>, enabled: Boolean)

    suspend fun delete(ids: List<Long>)

    suspend fun moveToEdge(ids: List<Long>, top: Boolean)

    suspend fun reorder(ids: List<Long>)

    suspend fun importDefault()

    suspend fun history(): List<String>

    suspend fun rememberUrl(url: String)

    suspend fun removeUrl(url: String)

    suspend fun json(rules: List<TxtTocRuleSnapshot>): String

    suspend fun shareFile(rules: List<TxtTocRuleSnapshot>): String

    suspend fun exportSummary(url: String): String

    suspend fun passphrase(url: String): String
}

class RoomTxtTocRuleManagementRepository(
    context: Context,
    private val database: AppDatabase = appDb,
) : TxtTocRuleManagementRepository {
    private val context = context.applicationContext
    private val historyMutex = Mutex()

    override fun observe() =
        database.txtTocRuleDao
            .observeAll()
            .map { rules -> rules.map(TxtTocRuleSnapshot::from) }
            .flowOn(Dispatchers.IO)

    override suspend fun setEnabled(ids: List<Long>, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val rules =
                    ids.distinct().mapNotNull(database.txtTocRuleDao::get).map {
                        it.copy(enable = enabled)
                    }
                database.txtTocRuleDao.update(*rules.toTypedArray())
            }
        }

    override suspend fun delete(ids: List<Long>) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                database.txtTocRuleDao.delete(
                    *ids.distinct().mapNotNull(database.txtTocRuleDao::get).toTypedArray()
                )
            }
        }

    override suspend fun reorder(ids: List<Long>) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val current = database.txtTocRuleDao.all
                val byId = current.associateBy { it.id }
                val order =
                    ids.distinct().filter(byId::containsKey) +
                        current.map { it.id }.filterNot(ids.toSet()::contains)
                val rules = order.mapIndexed { index, id ->
                    byId.getValue(id).copy(serialNumber = index + 1)
                }
                database.txtTocRuleDao.update(*rules.toTypedArray())
            }
        }

    override suspend fun moveToEdge(ids: List<Long>, top: Boolean) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val current = database.txtTocRuleDao.all
                val selected = ids.distinct().filter { id -> current.any { it.id == id } }
                val remaining = current.map { it.id }.filterNot(selected.toSet()::contains)
                val order = if (top) selected + remaining else remaining + selected
                val byId = current.associateBy { it.id }
                database.txtTocRuleDao.update(
                    *order
                        .mapIndexed { index, id ->
                            byId.getValue(id).copy(serialNumber = index + 1)
                        }
                        .toTypedArray()
                )
            }
        }

    override suspend fun importDefault() =
        withContext(Dispatchers.IO) { DefaultData.importDefaultTocRules() }

    override suspend fun history(): List<String> =
        withContext(Dispatchers.IO) {
            val urls =
                ACache.get(cacheDir = false)
                    .getAsString("tocRuleUrl")
                    ?.splitNotBlank(",")
                    ?.toList()
                    .orEmpty()
            if (DEFAULT_TXT_TOC_RULE_URL in urls) urls else listOf(DEFAULT_TXT_TOC_RULE_URL) + urls
        }

    override suspend fun rememberUrl(url: String) =
        withContext(Dispatchers.IO) {
            historyMutex.withLock {
                if (url.isAbsUrl()) {
                    val old = history()
                    if (url !in old)
                        ACache.get(cacheDir = false)
                            .put("tocRuleUrl", (listOf(url) + old).joinToString(","))
                }
            }
        }

    override suspend fun removeUrl(url: String) =
        withContext(Dispatchers.IO) {
            historyMutex.withLock {
                ACache.get(cacheDir = false)
                    .put("tocRuleUrl", history().filterNot { it == url }.joinToString(","))
            }
        }

    override suspend fun json(rules: List<TxtTocRuleSnapshot>): String =
        withContext(Dispatchers.Default) { GSON.toJson(rules.map { it.entity() }) }

    override suspend fun shareFile(rules: List<TxtTocRuleSnapshot>): String {
        var file: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val created = File.createTempFile("txtTocRule_", ".json", context.cacheDir)
                file = created
                created.writeText(json(rules))
                currentCoroutineContext().ensureActive()
                created.absolutePath
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { file?.delete() }
            throw error
        }
    }

    override suspend fun exportSummary(url: String): String =
        withContext(Dispatchers.IO) { if (url.isAbsUrl()) DirectLinkUpload.getSummary() else "" }

    override suspend fun passphrase(url: String): String =
        withContext(Dispatchers.IO) {
            SourceSharePassphrase.encode(
                url,
                SourceSharePassphrase.Type.TOC_RULE,
                DirectLinkUpload.getExpiryDate(),
            )
        }
}

const val DEFAULT_TXT_TOC_RULE_URL =
    "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json"
