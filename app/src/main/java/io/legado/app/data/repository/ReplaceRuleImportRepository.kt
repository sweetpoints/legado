package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.ReplaceAnalyzer
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.RuleUpdate
import io.legado.app.utils.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ReplaceRuleImportStatus {
    New,
    Update,
    Existing,
}

/** Immutable transport boundary: mutable Room entities never escape into UI state. */
data class ReplaceRuleImportItem(
    val key: String,
    val name: String,
    val json: String,
    val status: ReplaceRuleImportStatus,
) {
    val selectedByDefault: Boolean
        get() = status == ReplaceRuleImportStatus.New
}

data class ReplaceRuleImportSession(
    val items: List<ReplaceRuleImportItem>,
    val committed: Boolean = false,
)

interface ReplaceRuleImportRepository {
    suspend fun read(source: String): List<ReplaceRuleImportItem>

    suspend fun edit(key: String, code: String): ReplaceRuleImportItem

    suspend fun restore(session: String): ReplaceRuleImportSession?

    suspend fun stage(session: String, items: List<ReplaceRuleImportItem>)

    suspend fun release(session: String) {}

    suspend fun groups(): List<String>

    suspend fun insert(
        session: String,
        items: List<ReplaceRuleImportItem>,
        selected: Set<String>,
        group: String,
        add: Boolean,
    )
}

class AppReplaceRuleImportRepository(context: Context) : ReplaceRuleImportRepository {
    private val context = context.applicationContext

    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(context.cacheDir, "replace-rule-import/$session.json"))
    }

    private fun candidate(key: String, entity: ReplaceRule, local: ReplaceRule?) =
        ReplaceRuleImportItem(
            key,
            if (entity.group.isNullOrBlank()) entity.name else "${entity.name}(${entity.group})",
            GSON.toJson(entity),
            when {
                local == null -> ReplaceRuleImportStatus.New
                entity.pattern != local.pattern ||
                    entity.replacement != local.replacement ||
                    entity.isRegex != local.isRegex ||
                    entity.scope != local.scope -> ReplaceRuleImportStatus.Update
                else -> ReplaceRuleImportStatus.Existing
            },
        )

    override suspend fun read(source: String): List<ReplaceRuleImportItem> =
        withContext(Dispatchers.IO) {
            val incoming = parse(source.trim(), 0)
            var existing: Map<Long, ReplaceRule> = emptyMap()
            appDb.runInTransaction {
                existing =
                    incoming
                        .map { it.id }
                        .distinct()
                        .chunked(900)
                        .flatMap { appDb.replaceRuleDao.findByIds(*it.toLongArray()) }
                        .associateBy { it.id }
            }
            incoming.mapIndexed { index, item ->
                candidate(index.toString(), item, existing[item.id])
            }
        }

    private suspend fun parse(text: String, depth: Int): List<ReplaceRule> {
        require(depth < 32) { context.getString(R.string.wrong_format) }
        return when {
            text.isJsonObject() -> listOf(ReplaceAnalyzer.jsonToReplaceRule(text).getOrThrow())
            text.isJsonArray() -> ReplaceAnalyzer.jsonToReplaceRules(text).getOrThrow()
            text.isAbsUrl() -> {
                RuleUpdate.cacheReplaceRuleMap.remove(text)?.let {
                    return it
                }
                val body =
                    okHttpClient
                        .newCallResponseBody {
                            if (text.endsWith("#requestWithoutUA")) {
                                url(text.substringBeforeLast("#requestWithoutUA"))
                                header(AppConst.UA_NAME, "null")
                            } else url(text)
                        }
                        .decompressed()
                        .text("utf-8")
                parse(body.trim(), depth + 1)
            }
            text.isUri() -> parse(text.toUri().readText(context).trim(), depth + 1)
            else -> error(context.getString(R.string.wrong_format))
        }
    }

    override suspend fun edit(key: String, code: String): ReplaceRuleImportItem =
        withContext(Dispatchers.IO) {
            val entity = GSON.fromJsonObject<ReplaceRule>(code).getOrThrow()
            candidate(key, entity, appDb.replaceRuleDao.findById(entity.id))
        }

    override suspend fun restore(session: String): ReplaceRuleImportSession? =
        withContext(Dispatchers.IO) {
            val target = file(session)
            if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists())
                return@withContext null
            val data = JSONObject(target.openRead().bufferedReader().use { it.readText() })
            val array = data.getJSONArray("items")
            ReplaceRuleImportSession(
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    ReplaceRuleImportItem(
                        item.getString("key"),
                        item.getString("name"),
                        item.getString("json"),
                        ReplaceRuleImportStatus.valueOf(item.getString("status")),
                    )
                },
                data.optBoolean("committed"),
            )
        }

    private fun write(session: String, value: ReplaceRuleImportSession) {
        val array = JSONArray()
        value.items.forEach { item ->
            array.put(
                JSONObject()
                    .put("key", item.key)
                    .put("name", item.name)
                    .put("json", item.json)
                    .put("status", item.status.name)
            )
        }
        val target = file(session)
        val stream = target.startWrite()
        try {
            stream.write(
                JSONObject()
                    .put("items", array)
                    .put("committed", value.committed)
                    .toString()
                    .toByteArray()
            )
            target.finishWrite(stream)
        } catch (error: Throwable) {
            target.failWrite(stream)
            throw error
        }
    }

    override suspend fun stage(session: String, items: List<ReplaceRuleImportItem>) =
        withContext(Dispatchers.IO) {
            write(session, ReplaceRuleImportSession(items))
        }

    override suspend fun release(session: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            require(java.util.UUID.fromString(session).toString() == session)
            val target = file(session)
            target.delete()
            check(
                listOf(
                        target.baseFile,
                        File(target.baseFile.path + ".bak"),
                        File(target.baseFile.path + ".new"),
                    )
                    .none { it.exists() }
            )
        }

    override suspend fun groups(): List<String> =
        withContext(Dispatchers.IO) { appDb.replaceRuleDao.allGroups() }

    override suspend fun insert(
        session: String,
        items: List<ReplaceRuleImportItem>,
        selected: Set<String>,
        group: String,
        add: Boolean,
    ) =
        withContext(Dispatchers.IO + NonCancellable) {
            // A stopped/recreated host cannot cancel the commit between Room and the completion
            // marker.
            val entities =
                items
                    .filter { it.key in selected }
                    .map {
                        GSON.fromJsonObject<ReplaceRule>(it.json).getOrThrow().also { rule ->
                            rule.group = replaceImportGroup(rule.group, group, add)
                        }
                    }
            val inserted = appDb.replaceRuleDao.insert(*entities.toTypedArray())
            ReplacePreviewConfig.saveImportedSamples(entities, inserted)
            write(session, ReplaceRuleImportSession(items, committed = true))
        }
}

/** The entered group is one value; existing separator-delimited groups keep their order. */
internal fun replaceImportGroup(original: String?, entered: String, add: Boolean): String? {
    val group = entered.trim()
    if (group.isEmpty()) return original
    if (!add) return group
    val existing =
        original
            .orEmpty()
            .split(Regex("[,;，；]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(linkedSetOf())
    existing.add(group)
    return existing.joinToString(",")
}
