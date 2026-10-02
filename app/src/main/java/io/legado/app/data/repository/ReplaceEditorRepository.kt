package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.model.replace.ReplacePreview
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ReplaceEditorField { Name, Group, Pattern, Replacement, Scope, ExcludeScope, Timeout, Sample }
data class ReplaceEditorText(val text: String = "", val start: Int = 0, val end: Int = start) {
    fun bounded() = copy(start = start.coerceIn(0, text.length), end = end.coerceIn(0, text.length))
}
data class ReplaceEditorDraft(
    val id: Long = System.currentTimeMillis(), val enabled: Boolean = true, val order: Int = Int.MIN_VALUE,
    val fields: Map<ReplaceEditorField, ReplaceEditorText> = ReplaceEditorField.entries.associateWith {
        ReplaceEditorText(if (it == ReplaceEditorField.Timeout) "3000" else "") },
    val regex: Boolean = false, val title: Boolean = false, val source: Boolean = false, val content: Boolean = true
) {
    operator fun get(field: ReplaceEditorField) = fields[field] ?: ReplaceEditorText()
    fun with(field: ReplaceEditorField, value: ReplaceEditorText): ReplaceEditorDraft {
        val normalized = if (field == ReplaceEditorField.Sample) value.copy(text = ReplacePreview.normalizeSample(value.text)) else value
        return copy(fields = fields + (field to normalized.bounded()))
    }
    fun entity(export: Boolean = false) = ReplaceRule(id, get(ReplaceEditorField.Name).text,
        get(ReplaceEditorField.Group).text.ifBlank { null }, get(ReplaceEditorField.Pattern).text,
        get(ReplaceEditorField.Replacement).text, get(ReplaceEditorField.Scope).text.ifBlank { null },
        title, source, content, get(ReplaceEditorField.ExcludeScope).text.ifBlank { null }, enabled, regex,
        get(ReplaceEditorField.Timeout).text.toLongOrNull() ?: 3000L, order).also {
        if (export) it.previewText = get(ReplaceEditorField.Sample).text.takeIf(String::isNotEmpty)
    }
    /** ReplaceRule equality compares only its ID. Compare all editable values explicitly. */
    fun sameContent(other: ReplaceEditorDraft): Boolean {
        val a = entity(); val b = other.entity()
        return a.name == b.name && a.group == b.group && a.pattern == b.pattern && a.replacement == b.replacement &&
            a.scope == b.scope && a.excludeScope == b.excludeScope && a.timeoutMillisecond == b.timeoutMillisecond &&
            a.isRegex == b.isRegex && a.scopeTitle == b.scopeTitle && a.scopeSource == b.scopeSource &&
            a.scopeContent == b.scopeContent && a.isEnabled == b.isEnabled && a.order == b.order &&
            get(ReplaceEditorField.Sample).text == other[ReplaceEditorField.Sample].text
    }
    fun pasted(value: ReplaceEditorDraft) = value.copy(id = id, enabled = enabled, order = order)
    companion object {
        fun from(rule: ReplaceRule, sample: String = rule.previewText.orEmpty()) = ReplaceEditorDraft(rule.id,
            rule.isEnabled, rule.order, mapOf(ReplaceEditorField.Name to rule.name, ReplaceEditorField.Group to rule.group.orEmpty(),
                ReplaceEditorField.Pattern to rule.pattern, ReplaceEditorField.Replacement to rule.replacement,
                ReplaceEditorField.Scope to rule.scope.orEmpty(), ReplaceEditorField.ExcludeScope to rule.excludeScope.orEmpty(),
                ReplaceEditorField.Timeout to rule.timeoutMillisecond.toString(), ReplaceEditorField.Sample to ReplacePreview.normalizeSample(sample)
            ).mapValues { ReplaceEditorText(it.value) }, rule.isRegex, rule.scopeTitle, rule.scopeSource, rule.scopeContent)
    }
}
data class ReplaceEditorRequest(val id: Long = -1, val pattern: String = "", val regex: Boolean = false, val scope: String? = null)
private data class ReplaceEditorSaveJournal(val before: String?, val planned: String, val document: ReplaceEditorDocument)

data class ReplaceEditorDocument(val draft: ReplaceEditorDraft, val baseline: ReplaceEditorDraft = draft,
    val revision: Long = 0, val receipt: String? = null)
interface ReplaceEditorRepository {
    suspend fun load(request: ReplaceEditorRequest): ReplaceEditorDraft
    suspend fun read(session: String): ReplaceEditorDocument?
    suspend fun write(session: String, document: ReplaceEditorDocument)
    suspend fun save(session: String, document: ReplaceEditorDocument): ReplaceEditorDocument
    suspend fun parse(text: String, sampleId: Long): ReplaceEditorDraft
    suspend fun export(draft: ReplaceEditorDraft): String
    suspend fun preview(draft: ReplaceEditorDraft): String
    suspend fun editorInput(text: String): String
    suspend fun editorText(path: String): String
    suspend fun clearEditor(vararg paths: String?)
}
class RoomReplaceEditorRepository(context: Context, private val database: AppDatabase = appDb,
    private val directory: File = File(context.applicationContext.filesDir, "replace-editor-drafts"),
    private val failureHook: (String) -> Unit = {},
    private val transfer: CodeDialogTransferRepository = FileCodeDialogTransferRepository(context.applicationContext)
) : ReplaceEditorRepository {
    private val context = context.applicationContext
    override suspend fun load(request: ReplaceEditorRequest) = withContext(Dispatchers.IO) {
        val rule = if (request.id > 0) checkNotNull(database.replaceRuleDao.findById(request.id)) { "Rule no longer exists" }
        else ReplaceRule(name = request.pattern, pattern = request.pattern, isRegex = request.regex, scope = request.scope)
        ReplaceEditorDraft.from(rule, rule.previewText ?: ReplacePreviewConfig.sample(rule.id))
    }
    private fun file(session: String): AtomicFile {
        require(runCatching { UUID.fromString(session) }.isSuccess)
        return AtomicFile(File(directory, "$session.json"))
    }
    private fun readFile(session: String): ReplaceEditorDocument? {
        val value = file(session)
        if (!value.baseFile.exists() && !File(value.baseFile.path + ".bak").exists()) return null
        return value.openRead().bufferedReader().use { GSON.fromJsonObject<ReplaceEditorDocument>(it.readText()).getOrThrow() }
    }
    private fun writeFile(session: String, document: ReplaceEditorDocument) {
        val current = readFile(session)
        if (current != null && (current.receipt != null || current.revision > document.revision)) return
        directory.mkdirs(); val value = file(session); val output = value.startWrite()
        try { output.write(GSON.toJson(document).toByteArray()); value.finishWrite(output) }
        catch (error: Throwable) { value.failWrite(output); throw error }
    }
    private fun journalFile(session: String): AtomicFile {
        file(session) // Validate the session before forming a second path.
        return AtomicFile(File(directory, "$session.save.json"))
    }
    private fun readJournal(session: String): ReplaceEditorSaveJournal? {
        val value = journalFile(session)
        if (!value.baseFile.exists() && !File(value.baseFile.path + ".bak").exists()) return null
        return value.openRead().bufferedReader().use { GSON.fromJsonObject<ReplaceEditorSaveJournal>(it.readText()).getOrThrow() }
    }
    private fun writeJournal(session: String, journal: ReplaceEditorSaveJournal) {
        failureHook("beforeJournal")
        directory.mkdirs(); val value = journalFile(session); val output = value.startWrite()
        try { output.write(GSON.toJson(journal).toByteArray()); value.finishWrite(output) }
        catch (error: Throwable) { value.failWrite(output); throw error }
    }
    private fun fingerprint(rule: ReplaceRule?): String? = rule?.let {
        GSON.toJson(ReplaceEditorDraft.from(it, ""))
    }
    /** The journal is durable before either Room or sample preferences can change. */
    private fun recover(session: String): ReplaceEditorDocument? {
        val journal = readJournal(session) ?: return null
        val planned = journal.document.draft.entity()
        database.runInTransaction {
            val current = fingerprint(database.replaceRuleDao.findById(planned.id))
            check(current == journal.before || current == journal.planned) { "Rule changed while a save was pending" }
            if (current != journal.planned) database.replaceRuleDao.insert(planned)
        }
        failureHook("afterDatabase")
        failureHook("beforeSample")
        ReplacePreviewConfig.saveSample(planned.id, journal.document.draft[ReplaceEditorField.Sample].text)
        // The public sample helper uses apply(). Flush its pending preference write before
        // removing the recovery journal, so a successful receipt survives process death.
        check(context.getSharedPreferences("replace_preview", Context.MODE_PRIVATE).edit().commit()) { "Sample could not be persisted" }
        failureHook("beforeDraft")
        writeFile(session, journal.document)
        journalFile(session).delete()
        return journal.document
    }
    override suspend fun read(session: String) = withContext(Dispatchers.IO) {
        lock(session).withLock { recover(session) ?: readFile(session) }
    }
    override suspend fun write(session: String, document: ReplaceEditorDocument) = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            // A pending save owns the session until read/save completes its journal.
            if (readJournal(session) == null) writeFile(session, document)
        }
    }
    override suspend fun save(session: String, document: ReplaceEditorDocument) = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            recover(session)?.let { return@withLock it }
            val current = readFile(session)
            current?.takeIf { it.receipt != null }?.let { return@withLock it }
            check(current == null || current.revision <= document.revision) { "A newer rule draft exists" }
            val rule = document.draft.entity(); rule.checkValid()
            database.runInTransaction {
                val existing = database.replaceRuleDao.findById(rule.id)
                if (rule.order == Int.MIN_VALUE) rule.order = existing?.order ?: (database.replaceRuleDao.maxOrder + 1)
                val normalized = document.draft.copy(id = rule.id, order = rule.order)
                val result = document.copy(draft = normalized, baseline = normalized, revision = document.revision + 1,
                    receipt = UUID.randomUUID().toString())
                writeJournal(session, ReplaceEditorSaveJournal(fingerprint(existing), checkNotNull(fingerprint(rule)), result))
            }
            checkNotNull(recover(session))
        }
    }
    override suspend fun parse(text: String, sampleId: Long) = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "剪贴板为空" }
        val rule = GSON.fromJsonObject<ReplaceRule>(text).getOrNull() ?: error("格式不对")
        ReplaceEditorDraft.from(rule, rule.previewText ?: ReplacePreviewConfig.sample(sampleId))
    }
    override suspend fun export(draft: ReplaceEditorDraft) = withContext(Dispatchers.Default) { GSON.toJson(draft.entity(true)) }
    override suspend fun preview(draft: ReplaceEditorDraft) = ReplacePreview.apply(draft.entity(), draft[ReplaceEditorField.Sample].text)
    override suspend fun editorInput(text: String) = transfer.write(text)
    override suspend fun editorText(path: String) = transfer.read(path)
    override suspend fun clearEditor(vararg paths: String?) = transfer.delete(*paths)
    private fun lock(session: String) = locks.getOrPut(File(directory, session).absolutePath) { Mutex() }
    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
