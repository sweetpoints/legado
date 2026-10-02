package io.legado.app.data.repository

import com.google.gson.JsonObject
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Serialized sources preserve all book-source metadata while keeping mutable entities outside UI state. */
data class BookImportOriginal(val key: String, val json: String)
enum class BookImportStatus { New, Update, Existing, Error }
data class BookImportEntry(val key: String, val originalJson: String, val json: String,
    val replacedJson: String?, val replacementError: String?, val effectiveRuleIds: List<Long>,
    val localJson: String?, val sourceName: String, val sourceUrl: String, val sourceGroup: String?,
    val sourceComment: String?, val enabled: Boolean, val loginUrl: String?,
    val enabledExplore: Boolean, val needsLogin: Boolean,
    val status: BookImportStatus, val canImport: Boolean, val selectedByDefault: Boolean)
data class BookImportPreferences(val keepName: Boolean = false, val keepGroup: Boolean = false,
    val keepEnable: Boolean = false, val showComment: Boolean = false, val rememberGroup: Boolean = false,
    val lastGroup: String? = null, val lastGroupAdd: Boolean = false, val automaticReplacement: Boolean = false)
data class BookImportSnapshot(val items: List<BookImportEntry>, val automatic: Boolean,
    val manualIds: Map<String, List<Long>>, val committed: Boolean = false)

/** Platform I/O boundary, injected in tests to exercise the actual parser and replacement pipeline. */
interface BookImportStore {
    suspend fun uriText(uri: String): String
    suspend fun urlSources(url: String): List<BookSource>
    suspend fun javascriptSource(text: String): BookSource
    suspend fun sourceRules(): List<ReplaceRule>
    suspend fun existing(urls: List<String>): List<BookSource>
    suspend fun groups(): List<String>
    suspend fun preferences(): BookImportPreferences
    suspend fun preferences(value: BookImportPreferences)
    suspend fun insert(sources: List<BookSource>)
    suspend fun readSession(session: String): String?
    suspend fun writeSession(session: String, json: String)
}

interface BookImportRepository {
    suspend fun load(source: String): List<BookImportOriginal>
    suspend fun refresh(originals: List<BookImportOriginal>, automatic: Boolean,
        manualIds: Map<String, List<Long>>): List<BookImportEntry>
    suspend fun parseEdited(key: String, code: String): BookImportOriginal
    suspend fun groups(): List<String>
    suspend fun source(url: String): String?
    suspend fun preferences(): BookImportPreferences
    suspend fun preferences(value: BookImportPreferences)
    suspend fun restore(session: String): BookImportSnapshot?
    suspend fun stage(session: String, snapshot: BookImportSnapshot)
    suspend fun insert(session: String, snapshot: BookImportSnapshot, selected: Set<String>,
        preferences: BookImportPreferences, group: String?, addGroup: Boolean)
}

class DefaultBookImportRepository(private val store: BookImportStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : BookImportRepository {
    override suspend fun load(source: String): List<BookImportOriginal> = withContext(dispatcher) {
        parse(source.trim(), 0).mapIndexed { index, item -> BookImportOriginal(index.toString(), GSON.toJson(item)) }
    }
    private suspend fun parse(text: String, depth: Int): List<BookSource> {
        require(depth < 32) { "格式不对" }
        return when {
            text.isJsonArray() -> GSON.fromJsonArray<BookSource>(text).getOrThrow().onEach { it.requireSourceUrl() }
            text.isJsonObject() -> {
                val json = GSON.fromJsonObject<JsonObject>(text).getOrThrow()
                if (json.has("sourceUrls")) {
                    val urls = json.get("sourceUrls")
                    require(urls != null && !urls.isJsonNull) { "不是书源" }
                    GSON.fromJsonArray<String>(urls.toString()).getOrThrow().flatMap { url ->
                        require(url.isNotBlank()) { "不是书源" }; store.urlSources(url).onEach { it.requireSourceUrl() }
                    }
                } else listOf(single(text).also { it.requireSourceUrl() })
            }
            text.isAbsUrl() -> store.urlSources(text).onEach { it.requireSourceUrl() }
            text.isUri() -> parse(store.uriText(text).trim(), depth + 1)
            else -> listOf(store.javascriptSource(text).also { it.requireSourceUrl() })
        }
    }
    private fun single(code: String): BookSource = when {
        code.trim().isJsonObject() -> GSON.fromJsonObject<BookSource>(code.trim()).getOrThrow()
        code.trim().isJsonArray() -> GSON.fromJsonArray<BookSource>(code.trim()).getOrThrow().singleOrNull()
            ?: error("不是单个书源")
        else -> error("不是单个书源")
    }
    override suspend fun parseEdited(key: String, code: String): BookImportOriginal = withContext(dispatcher) {
        BookImportOriginal(key, GSON.toJson(single(code).also { it.requireSourceUrl() }))
    }
    override suspend fun refresh(originals: List<BookImportOriginal>, automatic: Boolean,
        manualIds: Map<String, List<Long>>): List<BookImportEntry> = withContext(dispatcher) {
        val rules = store.sourceRules()
        val useReplacement = automatic || manualIds.isNotEmpty()
        val prepared = originals.map { item ->
            val source = single(item.json).also { it.requireSourceUrl() }
            val selectedRules = if (automatic) rules else rules.filter { it.id in manualIds[item.key].orEmpty() }
            prepare(item.key, source, selectedRules, useReplacement)
        }
        val local = store.existing(prepared.map { it.source.bookSourceUrl }.distinct())
            .associateBy { it.bookSourceUrl }
        prepared.map { item ->
            val source = item.source; val existing = local[source.bookSourceUrl]
            val allowed = !useReplacement || item.error == null
            val status = when {
                !allowed -> BookImportStatus.Error
                existing == null -> BookImportStatus.New
                source.lastUpdateTime > existing.lastUpdateTime -> BookImportStatus.Update
                else -> BookImportStatus.Existing
            }
            BookImportEntry(item.key, item.original, GSON.toJson(source), item.replaced,
                item.error, item.ids.toList(), existing?.let { GSON.toJson(it) }, source.bookSourceName,
                source.bookSourceUrl, source.bookSourceGroup, source.bookSourceComment, source.enabled, source.loginUrl,
                source.enabledExplore, !source.loginUrl.isNullOrBlank() || (source.isJsSource() && source.hasLoginForm()),
                status, allowed, allowed && (existing == null || source.lastUpdateTime > existing.lastUpdateTime))
        }
    }
    private data class Prepared(val key: String, val original: String, val source: BookSource,
        val replaced: String?, val error: String?, val ids: List<Long>)
    private fun prepare(key: String, source: BookSource, rules: List<ReplaceRule>, use: Boolean): Prepared {
        val original = GSON.toJson(source); var replaced = original; val effective = mutableListOf<Long>()
        val matching = rules.filter { rule ->
            fun String.matches() = source.bookSourceName.isNotBlank() && contains(source.bookSourceName, true) ||
                source.bookSourceUrl.isNotBlank() && contains(source.bookSourceUrl, true)
            rule.isEnabled && rule.scopeSource && rule.pattern.isNotEmpty() &&
                (rule.scope.isNullOrEmpty() || rule.scope.orEmpty().matches()) &&
                (rule.excludeScope.isNullOrEmpty() || !rule.excludeScope.orEmpty().matches())
        }
        if (matching.isEmpty()) return Prepared(key, original, source, null, null, emptyList())
        return try {
            matching.forEach { rule ->
                val next = if (rule.isRegex) replaced.replace(rule.name, rule.regex, rule.replacement,
                    rule.getValidTimeoutMillisecond(), includeContentInTimeoutMessage = false)
                    else replaced.replace(rule.pattern, rule.replacement)
                if (next != replaced) effective += rule.id
                replaced = next
            }
            val parsed = single(replaced).also { it.requireSourceUrl() }
            Prepared(key, original, if (use) parsed else source, replaced, null, effective)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Prepared(key, original, source, replaced, error.localizedMessage ?: error.javaClass.simpleName, effective) }
    }
    override suspend fun source(url: String): String? = withContext(dispatcher) { store.existing(listOf(url)).firstOrNull { it.bookSourceUrl == url }?.let { GSON.toJson(it) } }
    override suspend fun groups() = withContext(dispatcher) { store.groups().toList() }
    override suspend fun preferences() = withContext(dispatcher) { store.preferences() }
    override suspend fun preferences(value: BookImportPreferences) = withContext(dispatcher) { store.preferences(value) }
    override suspend fun restore(session: String): BookImportSnapshot? = withContext(dispatcher) {
        store.readSession(session)?.let { GSON.fromJsonObject<BookImportSnapshot>(it).getOrThrow() }
    }
    override suspend fun stage(session: String, snapshot: BookImportSnapshot) = withContext(dispatcher) {
        store.writeSession(session, GSON.toJson(snapshot))
    }
    override suspend fun insert(session: String, snapshot: BookImportSnapshot, selected: Set<String>,
        preferences: BookImportPreferences, group: String?, addGroup: Boolean) = withContext(dispatcher + NonCancellable) {
        val sources = snapshot.items.filter { it.key in selected && it.canImport }.map { item ->
            single(item.json).also { source ->
                item.localJson?.let { single(it) }?.let { previous ->
                    if (preferences.keepName) source.bookSourceName = previous.bookSourceName
                    if (preferences.keepGroup) source.bookSourceGroup = previous.bookSourceGroup
                    if (preferences.keepEnable) { source.enabled = previous.enabled; source.enabledExplore = previous.enabledExplore }
                    source.customOrder = previous.customOrder
                }
                val entered = group?.trim()
                if (!entered.isNullOrEmpty()) source.bookSourceGroup = if (!addGroup) entered else
                    (source.bookSourceGroup.orEmpty().split(Regex("[,;，；]")).map { it.trim() }.filter { it.isNotEmpty() }
                        .toCollection(linkedSetOf()).apply { add(entered) }).joinToString(",")
            }
        }
        store.insert(sources)
        store.writeSession(session, GSON.toJson(snapshot.copy(committed = true)))
    }
}

private fun BookSource.requireSourceUrl() { require(bookSourceUrl.isNotBlank()) { "不是书源" } }
