package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Immutable transport boundary: mutable Room entities never escape into UI state. */
data class DictRuleImportItem(val key: String, val name: String, val json: String,
    val existsLocally: Boolean) {
    val selectedByDefault: Boolean get() = !existsLocally
}
data class DictRuleImportSession(val items: List<DictRuleImportItem>, val committed: Boolean = false)

interface DictRuleImportRepository {
    suspend fun read(source: String): List<DictRuleImportItem>
    suspend fun edit(key: String, code: String): DictRuleImportItem
    suspend fun restore(session: String): DictRuleImportSession?
    suspend fun stage(session: String, items: List<DictRuleImportItem>)
    suspend fun insert(session: String, items: List<DictRuleImportItem>, selected: Set<String>)
}

class AppDictRuleImportRepository(context: Context) : DictRuleImportRepository {
    private val context = context.applicationContext
    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(context.cacheDir, "dict-rule-import/$session.json"))
    }
    private fun candidate(key: String, entity: DictRule) = DictRuleImportItem(key, entity.name,
        GSON.toJson(entity), appDb.dictRuleDao.getByName(entity.name) != null)
    override suspend fun read(source: String): List<DictRuleImportItem> = withContext(Dispatchers.IO) {
        parse(source.trim(), 0).mapIndexed { index, item -> candidate(index.toString(), item) }
    }
    private suspend fun parse(text: String, depth: Int): List<DictRule> {
        require(depth < 32) { context.getString(R.string.wrong_format) }
        return when {
            text.isJsonObject() -> listOf(GSON.fromJsonObject<DictRule>(text).getOrThrow())
            text.isJsonArray() -> GSON.fromJsonArray<DictRule>(text).getOrThrow()
            text.isAbsUrl() -> {
                val body = okHttpClient.newCallResponseBody {
                    if (text.endsWith("#requestWithoutUA")) {
                        url(text.substringBeforeLast("#requestWithoutUA"))
                        header(AppConst.UA_NAME, "null")
                    } else url(text)
                }.decompressed().text()
                parse(body.trim(), depth + 1)
            }
            text.isUri() -> parse(text.toUri().readText(context).trim(), depth + 1)
            else -> error(context.getString(R.string.wrong_format))
        }
    }
    override suspend fun edit(key: String, code: String): DictRuleImportItem = withContext(Dispatchers.IO) {
        candidate(key, GSON.fromJsonObject<DictRule>(code).getOrThrow())
    }
    override suspend fun restore(session: String): DictRuleImportSession? = withContext(Dispatchers.IO) {
        val target = file(session)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return@withContext null
        val data = JSONObject(target.openRead().bufferedReader().use { it.readText() })
        val array = data.getJSONArray("items")
        DictRuleImportSession(List(array.length()) { index ->
            val item = array.getJSONObject(index)
            DictRuleImportItem(item.getString("key"), item.getString("name"), item.getString("json"),
                item.getBoolean("exists"))
        }, data.optBoolean("committed"))
    }
    private fun write(session: String, value: DictRuleImportSession) {
        val array = JSONArray()
        value.items.forEach { item -> array.put(JSONObject().put("key", item.key).put("name", item.name)
            .put("json", item.json).put("exists", item.existsLocally)) }
        val target = file(session)
        val stream = target.startWrite()
        try {
            stream.write(JSONObject().put("items", array).put("committed", value.committed).toString().toByteArray())
            target.finishWrite(stream)
        } catch (error: Throwable) { target.failWrite(stream); throw error }
    }
    override suspend fun stage(session: String, items: List<DictRuleImportItem>) = withContext(Dispatchers.IO) {
        write(session, DictRuleImportSession(items))
    }
    override suspend fun insert(session: String, items: List<DictRuleImportItem>, selected: Set<String>) =
        withContext(Dispatchers.IO + NonCancellable) {
            // A stopped/recreated host cannot cancel the commit between Room and the completion marker.
            val entities = items.filter { it.key in selected }.map { GSON.fromJsonObject<DictRule>(it.json).getOrThrow() }
            appDb.dictRuleDao.insert(*entities.toTypedArray())
            write(session, DictRuleImportSession(items, committed = true))
        }
}
