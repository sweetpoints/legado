package io.legado.app.data.repository

import com.google.gson.JsonObject
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.requireSourceUrl
import io.legado.app.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Serialized sources preserve all RSS metadata while keeping mutable entities outside UI state. */
data class RssImportOriginal(val key: String, val json: String)

enum class RssImportStatus {
    New,
    Update,
    Existing,
    Error,
}

data class RssImportEntry(
    val key: String,
    val originalJson: String,
    val json: String,
    val replacedJson: String?,
    val replacementError: String?,
    val effectiveRuleIds: List<Long>,
    val localJson: String?,
    val sourceName: String,
    val sourceUrl: String,
    val sourceGroup: String?,
    val sourceComment: String?,
    val enabled: Boolean,
    val loginUrl: String?,
    val status: RssImportStatus,
    val canImport: Boolean,
    val selectedByDefault: Boolean,
)

data class RssImportPreferences(
    val keepName: Boolean = false,
    val keepGroup: Boolean = false,
    val keepEnable: Boolean = false,
    val showComment: Boolean = false,
    val rememberGroup: Boolean = false,
    val lastGroup: String? = null,
    val lastGroupAdd: Boolean = false,
    val automaticReplacement: Boolean = false,
)

data class RssImportSnapshot(
    val items: List<RssImportEntry>,
    val automatic: Boolean,
    val manualIds: Map<String, List<Long>>,
    val committed: Boolean = false,
)

/**
 * Platform I/O boundary, injected in tests to exercise the actual parser and replacement pipeline.
 */
interface RssImportStore {
    suspend fun uriText(uri: String): String

    suspend fun urlSources(url: String): List<RssSource>

    suspend fun sourceRules(): List<ReplaceRule>

    suspend fun existing(urls: List<String>): List<RssSource>

    suspend fun groups(): List<String>

    suspend fun preferences(): RssImportPreferences

    suspend fun preferences(value: RssImportPreferences)

    suspend fun insert(sources: List<RssSource>)

    suspend fun readSession(session: String): String?

    suspend fun writeSession(session: String, json: String)
}

interface RssImportRepository {
    suspend fun load(source: String): List<RssImportOriginal>

    suspend fun refresh(
        originals: List<RssImportOriginal>,
        automatic: Boolean,
        manualIds: Map<String, List<Long>>,
    ): List<RssImportEntry>

    suspend fun parseEdited(key: String, code: String): RssImportOriginal

    suspend fun groups(): List<String>

    suspend fun preferences(): RssImportPreferences

    suspend fun preferences(value: RssImportPreferences)

    suspend fun restore(session: String): RssImportSnapshot?

    suspend fun stage(session: String, snapshot: RssImportSnapshot)

    suspend fun insert(
        session: String,
        snapshot: RssImportSnapshot,
        selected: Set<String>,
        preferences: RssImportPreferences,
        group: String?,
        addGroup: Boolean,
    )
}

class DefaultRssImportRepository(
    private val store: RssImportStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RssImportRepository {
    override suspend fun load(source: String): List<RssImportOriginal> =
        withContext(dispatcher) {
            parse(source.trim(), 0).mapIndexed { index, item ->
                RssImportOriginal(index.toString(), GSON.toJson(item))
            }
        }

    private suspend fun parse(text: String, depth: Int): List<RssSource> {
        require(depth < 32) { "格式不对" }
        return when {
            text.isJsonArray() ->
                GSON.fromJsonArray<RssSource>(text).getOrThrow().onEach { it.requireSourceUrl() }
            text.isJsonObject() -> {
                val json = GSON.fromJsonObject<JsonObject>(text).getOrThrow()
                if (json.has("sourceUrls")) {
                    val urls = json.get("sourceUrls")
                    require(urls != null && !urls.isJsonNull) { "不是订阅源" }
                    GSON.fromJsonArray<String>(urls.toString()).getOrThrow().flatMap { url ->
                        require(url.isNotBlank()) { "不是订阅源" }
                        store.urlSources(url)
                    }
                } else listOf(single(text).also { it.requireSourceUrl() })
            }
            text.isAbsUrl() -> store.urlSources(text)
            text.isUri() -> parse(store.uriText(text).trim(), depth + 1)
            else -> error("格式不对")
        }
    }

    private fun single(code: String): RssSource =
        when {
            code.trim().isJsonObject() -> GSON.fromJsonObject<RssSource>(code.trim()).getOrThrow()
            code.trim().isJsonArray() ->
                GSON.fromJsonArray<RssSource>(code.trim()).getOrThrow().singleOrNull()
                    ?: error("不是单个订阅源")
            else -> error("不是单个订阅源")
        }

    override suspend fun parseEdited(key: String, code: String): RssImportOriginal =
        withContext(dispatcher) {
            RssImportOriginal(key, GSON.toJson(single(code).also { it.requireSourceUrl() }))
        }

    override suspend fun refresh(
        originals: List<RssImportOriginal>,
        automatic: Boolean,
        manualIds: Map<String, List<Long>>,
    ): List<RssImportEntry> =
        withContext(dispatcher) {
            val rules = store.sourceRules()
            val useReplacement = automatic || manualIds.isNotEmpty()
            val prepared = originals.map { item ->
                val source = single(item.json).also { it.requireSourceUrl() }
                val selectedRules =
                    if (automatic) rules
                    else rules.filter { it.id in manualIds[item.key].orEmpty() }
                prepare(item.key, source, selectedRules, useReplacement)
            }
            val local =
                store.existing(prepared.map { it.source.sourceUrl }.distinct()).associateBy {
                    it.sourceUrl
                }
            prepared.map { item ->
                val source = item.source
                val existing = local[source.sourceUrl]
                val allowed = !useReplacement || item.error == null
                val status =
                    when {
                        !allowed -> RssImportStatus.Error
                        existing == null -> RssImportStatus.New
                        source.lastUpdateTime > existing.lastUpdateTime -> RssImportStatus.Update
                        else -> RssImportStatus.Existing
                    }
                RssImportEntry(
                    item.key,
                    item.original,
                    GSON.toJson(source),
                    item.replaced,
                    item.error,
                    item.ids.toList(),
                    existing?.let { GSON.toJson(it) },
                    source.sourceName,
                    source.sourceUrl,
                    source.sourceGroup,
                    source.sourceComment,
                    source.enabled,
                    source.loginUrl,
                    status,
                    allowed,
                    allowed &&
                        (existing == null || source.lastUpdateTime > existing.lastUpdateTime),
                )
            }
        }

    private data class Prepared(
        val key: String,
        val original: String,
        val source: RssSource,
        val replaced: String?,
        val error: String?,
        val ids: List<Long>,
    )

    private fun prepare(
        key: String,
        source: RssSource,
        rules: List<ReplaceRule>,
        use: Boolean,
    ): Prepared {
        val original = GSON.toJson(source)
        var replaced = original
        val effective = mutableListOf<Long>()
        val matching = rules.filter { rule ->
            fun String.matches() =
                source.sourceName.isNotBlank() && contains(source.sourceName, true) ||
                    source.sourceUrl.isNotBlank() && contains(source.sourceUrl, true)
            rule.isEnabled &&
                rule.scopeSource &&
                rule.pattern.isNotEmpty() &&
                (rule.scope.isNullOrEmpty() || rule.scope.orEmpty().matches()) &&
                (rule.excludeScope.isNullOrEmpty() || !rule.excludeScope.orEmpty().matches())
        }
        if (matching.isEmpty()) return Prepared(key, original, source, null, null, emptyList())
        return try {
            matching.forEach { rule ->
                val next =
                    if (rule.isRegex)
                        replaced.replace(
                            rule.name,
                            rule.regex,
                            rule.replacement,
                            rule.getValidTimeoutMillisecond(),
                            includeContentInTimeoutMessage = false,
                        )
                    else replaced.replace(rule.pattern, rule.replacement)
                if (next != replaced) effective += rule.id
                replaced = next
            }
            val parsed = single(replaced).also { it.requireSourceUrl() }
            Prepared(key, original, if (use) parsed else source, replaced, null, effective)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Prepared(
                key,
                original,
                source,
                replaced,
                error.localizedMessage ?: error.javaClass.simpleName,
                effective,
            )
        }
    }

    override suspend fun groups() = withContext(dispatcher) { store.groups().toList() }

    override suspend fun preferences() = withContext(dispatcher) { store.preferences() }

    override suspend fun preferences(value: RssImportPreferences) =
        withContext(dispatcher) { store.preferences(value) }

    override suspend fun restore(session: String): RssImportSnapshot? =
        withContext(dispatcher) {
            store.readSession(session)?.let {
                GSON.fromJsonObject<RssImportSnapshot>(it).getOrThrow()
            }
        }

    override suspend fun stage(session: String, snapshot: RssImportSnapshot) =
        withContext(dispatcher) {
            store.writeSession(session, GSON.toJson(snapshot))
        }

    override suspend fun insert(
        session: String,
        snapshot: RssImportSnapshot,
        selected: Set<String>,
        preferences: RssImportPreferences,
        group: String?,
        addGroup: Boolean,
    ) =
        withContext(dispatcher + NonCancellable) {
            val sources =
                snapshot.items
                    .filter { it.key in selected && it.canImport }
                    .map { item ->
                        single(item.json).also { source ->
                            item.localJson
                                ?.let { single(it) }
                                ?.let { previous ->
                                    if (preferences.keepName)
                                        source.sourceName = previous.sourceName
                                    if (preferences.keepGroup)
                                        source.sourceGroup = previous.sourceGroup
                                    if (preferences.keepEnable) source.enabled = previous.enabled
                                    source.customOrder = previous.customOrder
                                }
                            val entered = group?.trim()
                            if (!entered.isNullOrEmpty())
                                source.sourceGroup =
                                    if (!addGroup) entered
                                    else
                                        (source.sourceGroup
                                                .orEmpty()
                                                .split(Regex("[,;，；]"))
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() }
                                                .toCollection(linkedSetOf())
                                                .apply { add(entered) })
                                            .joinToString(",")
                        }
                    }
            store.insert(sources)
            store.writeSession(session, GSON.toJson(snapshot.copy(committed = true)))
        }
}
