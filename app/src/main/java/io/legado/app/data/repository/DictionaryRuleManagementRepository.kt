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

interface DictionaryRuleManagementRepository {
    fun observe(): Flow<List<DictionaryRuleSnapshot>>

    suspend fun setEnabled(names: List<String>, enabled: Boolean)

    suspend fun delete(names: List<String>)

    suspend fun reorder(names: List<String>)

    suspend fun importDefault()

    suspend fun history(): List<String>

    suspend fun rememberUrl(url: String)

    suspend fun removeUrl(url: String)

    suspend fun json(rules: List<DictionaryRuleSnapshot>): String

    suspend fun shareFile(rules: List<DictionaryRuleSnapshot>): String

    suspend fun exportSummary(url: String): String

    suspend fun passphrase(url: String): String
}

class RoomDictionaryRuleManagementRepository(
    context: Context,
    private val database: AppDatabase = appDb,
) : DictionaryRuleManagementRepository {
    private val context = context.applicationContext
    private val historyMutex = Mutex()

    override fun observe() =
        database.dictRuleDao
            .flowAll()
            .map { rules -> rules.map(DictionaryRuleSnapshot::from) }
            .flowOn(Dispatchers.IO)

    override suspend fun setEnabled(names: List<String>, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val rules =
                    names.distinct().mapNotNull(database.dictRuleDao::getByName).map {
                        it.copy(enabled = enabled)
                    }
                database.dictRuleDao.update(*rules.toTypedArray())
            }
        }

    override suspend fun delete(names: List<String>) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                database.dictRuleDao.delete(
                    *names.distinct().mapNotNull(database.dictRuleDao::getByName).toTypedArray()
                )
            }
        }

    override suspend fun reorder(names: List<String>) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val current = database.dictRuleDao.all
                val byName = current.associateBy { it.name }
                val order =
                    names.distinct().filter(byName::containsKey) +
                        current.map { it.name }.filterNot(names.toSet()::contains)
                val rules = order.mapIndexed { index, name ->
                    byName.getValue(name).copy(sortNumber = index + 1)
                }
                database.dictRuleDao.update(*rules.toTypedArray())
            }
        }

    override suspend fun importDefault() =
        withContext(Dispatchers.IO) { DefaultData.importDefaultDictRules() }

    override suspend fun history(): List<String> =
        withContext(Dispatchers.IO) {
            ACache.get(cacheDir = false)
                .getAsString("dictRuleUrls")
                ?.splitNotBlank(",")
                ?.toList()
                .orEmpty()
        }

    override suspend fun rememberUrl(url: String) =
        withContext(Dispatchers.IO) {
            historyMutex.withLock {
                if (url.isAbsUrl()) {
                    val old = history()
                    if (url !in old)
                        ACache.get(cacheDir = false)
                            .put("dictRuleUrls", (listOf(url) + old).joinToString(","))
                }
            }
        }

    override suspend fun removeUrl(url: String) =
        withContext(Dispatchers.IO) {
            historyMutex.withLock {
                ACache.get(cacheDir = false)
                    .put("dictRuleUrls", history().filterNot { it == url }.joinToString(","))
            }
        }

    override suspend fun json(rules: List<DictionaryRuleSnapshot>): String =
        withContext(Dispatchers.Default) { GSON.toJson(rules.map { it.entity() }) }

    override suspend fun shareFile(rules: List<DictionaryRuleSnapshot>): String {
        var file: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val created = File.createTempFile("dictRule_", ".json", context.cacheDir)
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
                SourceSharePassphrase.Type.DICT_RULE,
                DirectLinkUpload.getExpiryDate(),
            )
        }
}
