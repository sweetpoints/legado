package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.utils.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class HighlightImportStatus {
    NEW,
    UPDATE,
    EXISTING,
}

data class HighlightImportComparison(
    val rule: HighlightRule,
    val status: HighlightImportStatus,
)

fun parseHighlightImportFile(text: String): List<HighlightRule> {
    val parsed = GSONStrict.fromJsonObject<JsonElement>(text).getOrThrow()
    val root =
        if (parsed.isJsonArray) {
            parsed.asJsonArray.forEach { element ->
                require(element.isJsonObject)
                val rule = element.asJsonObject
                require(rule.has("pattern") && rule.has("style") && rule.has("uuid"))
                require(!rule.has("replacement"))
            }
            JsonObject().apply {
                addProperty("type", HighlightRuleFile.TYPE)
                add("rules", parsed)
            }
        } else {
            require(parsed.isJsonObject)
            parsed.asJsonObject
        }
    val rules = root.get("rules")
    require(rules != null && rules.isJsonArray)
    rules.asJsonArray.forEach { element ->
        require(element.isJsonObject)
        val rule = element.asJsonObject
        val uuid = rule.get("uuid")
        require(
            uuid != null &&
                !uuid.isJsonNull &&
                uuid.isJsonPrimitive &&
                uuid.asJsonPrimitive.isString
        )
        listOf("name", "pattern", "style").forEach { field ->
            rule.get(field)?.let { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            }
        }
        rule.get("scope")?.let { value ->
            require(value.isJsonNull || value.isJsonPrimitive && value.asJsonPrimitive.isString)
        }
        listOf("isRegex", "isEnabled", "applyToTitle", "applyToBody").forEach { field ->
            rule.get(field)?.let { value ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
            }
        }
        listOf("id", "timeoutMillisecond").forEach { field ->
            rule.get(field)?.let { value ->
                require(
                    value.isJsonPrimitive &&
                        value.asJsonPrimitive.isNumber &&
                        runCatching { value.asBigDecimal.longValueExact() }.isSuccess
                )
            }
        }
        rule.get("order")?.let { value ->
            require(
                value.isJsonPrimitive &&
                    value.asJsonPrimitive.isNumber &&
                    runCatching { value.asBigDecimal.intValueExact() }.isSuccess
            )
        }
    }
    return validateHighlightImportFile(GSONStrict.fromJson(root, HighlightRuleFile::class.java))
}

fun validateHighlightImportFile(file: HighlightRuleFile): List<HighlightRule> {
    require(file.type == HighlightRuleFile.TYPE)
    val rules = file.rules ?: error("Missing rules")
    val uuids = hashSetOf<String>()
    return rules.map { nullableRule ->
        val rule = nullableRule ?: error("Invalid rule")
        @Suppress("USELESS_CAST") val rawUuid = (rule.uuid as String?).orEmpty()
        val uuid = UUID.fromString(rawUuid).toString()
        require(uuid.equals(rawUuid, ignoreCase = true))
        require(uuids.add(uuid))
        rule.uuid = uuid
        rule.scope = rule.scope?.ifBlank { null }
        rule.normalizeForRestore()
        require(rule.isValid())
        rule
    }
}

fun compareHighlightImports(
    imported: List<HighlightRule>,
    local: List<HighlightRule>,
): List<HighlightImportComparison> {
    val localByUuid = local.associateBy { it.uuid.lowercase() }
    return imported.map { rule ->
        val existing = localByUuid[rule.uuid.lowercase()]
        val status =
            when {
                existing == null -> HighlightImportStatus.NEW
                GSON.toJsonTree(existing.copy(id = 0L, order = 0)) ==
                    GSON.toJsonTree(rule.copy(id = 0L, order = 0)) -> HighlightImportStatus.EXISTING
                else -> HighlightImportStatus.UPDATE
            }
        HighlightImportComparison(rule, status)
    }
}

data class HighlightImportItem(
    val key: String,
    val name: String,
    val json: String,
    val status: HighlightImportStatus,
) {
    val selectedByDefault: Boolean
        get() = status != HighlightImportStatus.EXISTING
}

data class HighlightImportSession(
    val items: List<HighlightImportItem>,
    val committed: Boolean = false,
)

interface HighlightImportRepository {
    suspend fun read(source: String): List<HighlightImportItem>

    suspend fun restore(session: String): HighlightImportSession?

    suspend fun stage(session: String, items: List<HighlightImportItem>)

    suspend fun insert(session: String, items: List<HighlightImportItem>, selected: Set<String>)
}

class AppHighlightImportRepository(context: Context) : HighlightImportRepository {
    private val context = context.applicationContext

    private fun file(session: String): AtomicFile {
        require(session.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(context.cacheDir, "highlight-rule-import/$session.json"))
    }

    override suspend fun read(source: String): List<HighlightImportItem> =
        withContext(Dispatchers.IO) {
            val imported = parseHighlightImportFile(source.toUri().readText(context))
            compareHighlightImports(imported, appDb.highlightRuleDao.all).map { value ->
                HighlightImportItem(
                    value.rule.uuid,
                    value.rule.getDisplayName(),
                    GSON.toJson(value.rule),
                    value.status,
                )
            }
        }

    override suspend fun restore(session: String): HighlightImportSession? =
        withContext(Dispatchers.IO) {
            val target = file(session)
            if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists())
                return@withContext null
            GSON.fromJsonObject<HighlightImportSession>(
                    target.openRead().bufferedReader().use { it.readText() }
                )
                .getOrThrow()
        }

    private fun write(session: String, value: HighlightImportSession) {
        val target = file(session)
        val stream = target.startWrite()
        try {
            stream.write(GSON.toJson(value).toByteArray())
            target.finishWrite(stream)
        } catch (error: Throwable) {
            target.failWrite(stream)
            throw error
        }
    }

    override suspend fun stage(session: String, items: List<HighlightImportItem>) =
        withContext(Dispatchers.IO) {
            write(session, HighlightImportSession(items))
        }

    override suspend fun insert(
        session: String,
        items: List<HighlightImportItem>,
        selected: Set<String>,
    ) =
        withContext(Dispatchers.IO + NonCancellable) {
            val rules =
                items
                    .filter { it.key in selected }
                    .map { GSON.fromJsonObject<HighlightRule>(it.json).getOrThrow() }
            require(rules.isNotEmpty())
            appDb.highlightRuleDao.importRules(rules)
            write(session, HighlightImportSession(items, committed = true))
        }
}
