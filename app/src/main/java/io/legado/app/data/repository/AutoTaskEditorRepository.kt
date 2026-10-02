package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.model.AutoTask
import io.legado.app.service.AutoTaskScheduler
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class AutoTaskEditorField(val code: Boolean = false) {
    Name, Cron, Comment, Script(true), Header(true), JsLib(true), ConcurrentRate, LoginUrl(true), LoginUi(true), LoginCheckJs(true)
}
data class AutoTaskEditorText(val text: String = "", val start: Int = 0, val end: Int = start) {
    fun bounded() = copy(start = start.coerceIn(0, text.length), end = end.coerceIn(0, text.length))
}
enum class AutoTaskEditorIssue { Name, Cron, Script, Format, Focus, NoLogin }
enum class AutoTaskEditorSaveAction { Close, Debug, Login }
data class AutoTaskEditorDraft(val fields: Map<AutoTaskEditorField, AutoTaskEditorText> = AutoTaskEditorField.entries.associateWith {
    AutoTaskEditorText(if (it == AutoTaskEditorField.Cron) AutoTask.DEFAULT_CRON else "") },
    val enabled: Boolean = true, val cookieJar: Boolean = true) {
    operator fun get(field: AutoTaskEditorField) = fields[field] ?: AutoTaskEditorText()
    fun with(field: AutoTaskEditorField, value: AutoTaskEditorText) = copy(fields = fields + (field to value.bounded()))
    fun entity(id: String, base: AutoTaskRule = AutoTaskRule(id = id)): AutoTaskRule {
        fun text(field: AutoTaskEditorField) = get(field).text.trim().ifBlank { null }
        return base.copy(id = id, name = get(AutoTaskEditorField.Name).text.trim(), enable = enabled,
            cron = get(AutoTaskEditorField.Cron).text.trim(), comment = text(AutoTaskEditorField.Comment),
            script = get(AutoTaskEditorField.Script).text, header = text(AutoTaskEditorField.Header), jsLib = text(AutoTaskEditorField.JsLib),
            concurrentRate = text(AutoTaskEditorField.ConcurrentRate), loginUrl = text(AutoTaskEditorField.LoginUrl),
            loginUi = text(AutoTaskEditorField.LoginUi), loginCheckJs = text(AutoTaskEditorField.LoginCheckJs), enabledCookieJar = cookieJar)
    }
    fun validation(): AutoTaskEditorIssue? = when {
        get(AutoTaskEditorField.Name).text.isBlank() -> AutoTaskEditorIssue.Name
        CronSchedule.parse(get(AutoTaskEditorField.Cron).text.trim()) == null -> AutoTaskEditorIssue.Cron
        AutoTask.normalizeScript(get(AutoTaskEditorField.Script).text).isBlank() -> AutoTaskEditorIssue.Script
        else -> null
    }
    /** Cursor movement alone is not a content change. */
    fun sameContent(other: AutoTaskEditorDraft) = entity("draft") == other.entity("draft")
    companion object {
        fun from(rule: AutoTaskRule) = AutoTaskEditorDraft(mapOf(
            AutoTaskEditorField.Name to rule.name.orEmpty(), AutoTaskEditorField.Cron to (rule.cron ?: AutoTask.DEFAULT_CRON),
            AutoTaskEditorField.Comment to rule.comment.orEmpty(), AutoTaskEditorField.Script to rule.script.orEmpty(),
            AutoTaskEditorField.Header to rule.header.orEmpty(), AutoTaskEditorField.JsLib to rule.jsLib.orEmpty(),
            AutoTaskEditorField.ConcurrentRate to rule.concurrentRate.orEmpty(), AutoTaskEditorField.LoginUrl to rule.loginUrl.orEmpty(),
            AutoTaskEditorField.LoginUi to rule.loginUi.orEmpty(), AutoTaskEditorField.LoginCheckJs to rule.loginCheckJs.orEmpty()
        ).mapValues { AutoTaskEditorText(it.value) }, rule.enable, rule.enabledCookieJar)
    }
}
data class AutoTaskEditorDelivery(val token: String, val action: AutoTaskEditorSaveAction, val loginAvailable: Boolean)
data class AutoTaskEditorDocument(val id: String, val draft: AutoTaskEditorDraft, val baseline: AutoTaskEditorDraft = draft,
    val existing: Boolean = false, val revision: Long = 0, val delivery: AutoTaskEditorDelivery? = null)
interface AutoTaskEditorRepository {
    suspend fun load(id: String): AutoTaskEditorDraft?
    suspend fun readDraft(session: String): AutoTaskEditorDocument?
    suspend fun writeDraft(session: String, document: AutoTaskEditorDocument)
    suspend fun save(session: String, document: AutoTaskEditorDocument, action: AutoTaskEditorSaveAction): AutoTaskEditorDocument
    suspend fun parse(text: String): AutoTaskEditorDraft?
    suspend fun export(id: String, draft: AutoTaskEditorDraft): String
    suspend fun editorInput(text: String): String
    suspend fun editorText(path: String): String
    suspend fun clearEditor(vararg paths: String?)
}
class RoomAutoTaskEditorRepository(context: Context, private val database: AppDatabase = appDb,
    private val initialize: () -> Unit = { if (database === appDb) AutoTask.all() }, refresh: (() -> Unit)? = null,
    directory: File = File(context.applicationContext.filesDir, "auto-task-editor"),
    private val transfer: CodeDialogTransferRepository = FileCodeDialogTransferRepository(context.applicationContext),
) : AutoTaskEditorRepository {
    private val directory = directory
    private val context = context.applicationContext
    private val schedule = refresh ?: { AutoTaskScheduler.refresh(this.context); Unit }
    override suspend fun load(id: String) = withContext(Dispatchers.IO) {
        synchronized(AutoTask) { initialize(); database.autoTaskRuleDao.getById(id)?.let(AutoTaskEditorDraft::from) }
    }
    private fun file(session: String): AtomicFile {
        require(runCatching { UUID.fromString(session) }.isSuccess) { "Invalid draft session" }
        return AtomicFile(File(directory, "$session.json"))
    }
    private fun read(session: String): AutoTaskEditorDocument? {
        val file = file(session)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use { GSON.fromJsonObject<AutoTaskEditorDocument>(it.readText()).getOrThrow() }
    }
    override suspend fun readDraft(session: String) = withContext(Dispatchers.IO) { lock(session).withLock { read(session) } }
    override suspend fun writeDraft(session: String, document: AutoTaskEditorDocument) = withContext(Dispatchers.IO + NonCancellable) {
        lock(session).withLock {
            if ((read(session)?.revision ?: -1) <= document.revision) {
                directory.mkdirs(); val file = file(session); val output = file.startWrite()
                try { output.write(GSON.toJson(document).toByteArray()); file.finishWrite(output) }
                catch (error: Throwable) { file.failWrite(output); throw error }
            }
        }
    }
    override suspend fun save(session: String, document: AutoTaskEditorDocument, action: AutoTaskEditorSaveAction): AutoTaskEditorDocument = withContext(Dispatchers.IO + NonCancellable) {
        check(document.draft.validation() == null)
        val persisted = synchronized(AutoTask) {
            initialize(); var persisted: AutoTaskRule? = null
            database.runInTransaction {
                val current = database.autoTaskRuleDao.getById(document.id)
                check(!document.existing || current != null) { "Task no longer exists" }
                val base = current ?: AutoTaskRule(id = document.id, customOrder = database.autoTaskRuleDao.maxOrder() + 1)
                persisted = document.draft.entity(document.id, base)
                database.autoTaskRuleDao.upsert(requireNotNull(persisted))
            }; requireNotNull(persisted)
        }
        schedule()
        val normalized = AutoTaskEditorDraft.from(persisted)
        val draft = normalized.copy(fields = normalized.fields.mapValues { (field, value) ->
            value.copy(start = document.draft[field].start, end = document.draft[field].end).bounded()
        })
        val result = document.copy(draft = draft, baseline = draft, existing = true, revision = document.revision + 1,
            delivery = AutoTaskEditorDelivery(UUID.randomUUID().toString(), action, AutoTask.buildSource(persisted).hasLogin()))
        writeDraft(session, result); result
    }
    override suspend fun parse(text: String) = withContext(Dispatchers.Default) {
        (GSON.fromJsonObject<AutoTaskRule>(text).getOrNull() ?: GSON.fromJsonArray<AutoTaskRule>(text).getOrNull()?.singleOrNull())?.let(AutoTaskEditorDraft::from)
    }
    override suspend fun export(id: String, draft: AutoTaskEditorDraft) = withContext(Dispatchers.Default) { AutoTask.exportJson(listOf(draft.entity(id))) }
    override suspend fun editorInput(text: String) = transfer.write(text)
    override suspend fun editorText(path: String) = transfer.read(path)
    override suspend fun clearEditor(vararg paths: String?) = transfer.delete(*paths)
    private fun lock(session: String) = locks.getOrPut(File(directory, session).absolutePath) { Mutex() }
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
