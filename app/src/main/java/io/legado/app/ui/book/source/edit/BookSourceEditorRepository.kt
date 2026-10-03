package io.legado.app.ui.book.source.edit

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.CodeDialogTransferRepository
import io.legado.app.data.repository.FileCodeDialogTransferRepository
import io.legado.app.help.AppCacheManager
import io.legado.app.help.ConcurrentRateLimiter.Companion.concurrentRecordMap
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.clearSharedGlobalState
import io.legado.app.help.storage.ImportOldData
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.jsonPath
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
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

internal class BookSourceDraftConflict : IllegalStateException("书源草稿已由其他编辑会话更新，请点击重试读取已保存的草稿")

internal data class BookSourceKeyboardAssist(val key: String, val value: String)

internal interface BookSourceEditorRepository {
    fun assists(): Flow<List<BookSourceKeyboardAssist>>

    suspend fun load(sourceUrl: String?): BookSourceEditDocument

    suspend fun readDraft(sessionId: String): BookSourceEditDocument?

    suspend fun writeDraft(sessionId: String, document: BookSourceEditDocument): Boolean

    suspend fun save(
        sessionId: String,
        document: BookSourceEditDocument,
        action: BookSourceSaveAction,
    ): BookSourceEditDocument

    suspend fun importForm(text: String): BookSourceEditForm

    suspend fun searchScope(sourceUrl: String): String

    suspend fun parse(text: String): String

    suspend fun export(document: BookSourceEditDocument): String

    suspend fun groups(): List<String>

    suspend fun clearCookie(sourceUrl: String)

    suspend fun variableComment(sourceUrl: String): String

    suspend fun variable(sourceUrl: String): String

    suspend fun setVariable(sourceUrl: String, value: String?)

    suspend fun transfer(text: String): String

    suspend fun editorText(path: String): String

    suspend fun release(vararg paths: String?)
}

internal class RoomBookSourceEditorRepository(
    context: Context,
    private val database: AppDatabase = appDb,
    private val directory: File =
        File(context.applicationContext.filesDir, "book-source-edit-drafts"),
    private val transfers: CodeDialogTransferRepository = FileCodeDialogTransferRepository(context),
    private val now: () -> Long = System::currentTimeMillis,
    private val beforeJournalWrite: suspend () -> Unit = {},
    private val beforeDraftWrite: suspend () -> Unit = {},
    private val invalidate: suspend (BookSource?, BookSource) -> Unit = ::invalidateBookSource,
) : BookSourceEditorRepository {
    private val context = context.applicationContext

    override fun assists(): Flow<List<BookSourceKeyboardAssist>> =
        database.keyboardAssistsDao
            .flowByType(0)
            .map { items -> items.map { BookSourceKeyboardAssist(it.key, it.value) } }
            .flowOn(Dispatchers.IO)

    override suspend fun load(sourceUrl: String?): BookSourceEditDocument =
        withContext(Dispatchers.IO) {
            val source = sourceUrl?.let(database.bookSourceDao::getBookSource)
            BookSourceEditDocument.from(source ?: BookSource(), source?.bookSourceUrl)
        }

    private fun file(sessionId: String, journal: Boolean = false): AtomicFile {
        UUID.fromString(sessionId)
        val suffix = if (journal) "-save" else ""
        return AtomicFile(File(directory, "$sessionId$suffix.json"))
    }

    private fun draftLock(sessionId: String): Mutex = lock(draftLocks, sessionId)

    private fun saveLock(sessionId: String): Mutex = lock(saveLocks, sessionId)

    private fun lock(locks: Array<Mutex>, sessionId: String): Mutex {
        val identity = File(directory, sessionId).absolutePath
        return locks[(identity.hashCode() and Int.MAX_VALUE) % locks.size]
    }

    private inline fun <reified T> readFile(target: AtomicFile): T? {
        val input =
            try {
                target.openRead()
            } catch (_: FileNotFoundException) {
                return null
            }
        return input.bufferedReader().use { GSON.fromJsonObject<T>(it.readText()).getOrThrow() }
    }

    private fun writeFile(target: AtomicFile, value: Any) {
        check(directory.isDirectory || directory.mkdirs()) { "无法保存书源草稿" }
        val output = target.startWrite()
        try {
            output.write(GSON.toJson(value).toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (error: Throwable) {
            target.failWrite(output)
            throw error
        }
    }

    override suspend fun readDraft(sessionId: String): BookSourceEditDocument? =
        withContext(Dispatchers.IO) {
            saveLock(sessionId).withLock {
                readFile<PendingSave>(file(sessionId, journal = true))?.let { pending ->
                    // The journal contains a fixed plan, not executable JS. Completing its receipt
                    // never re-materializes rules or overwrites a concurrent source edit.
                    withContext(NonCancellable) {
                        try {
                            finishSave(sessionId, pending)
                        } catch (_: BookSourceDraftConflict) {
                            // The accepted save plan remains durable in its journal. A newer owner
                            // controls navigation; reading must never publish the obsolete receipt.
                        }
                    }
                }
                draftLock(sessionId).withLock { readFile(file(sessionId)) }
            }
        }

    override suspend fun writeDraft(sessionId: String, document: BookSourceEditDocument): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            draftLock(sessionId).withLock {
                val previous = readFile<BookSourceEditDocument>(file(sessionId))
                // Compare the entire immutable document, including native owner and raw fields.
                // Entity equality only checks the URL and cannot prove checkpoint acceptance.
                if (previous == document) return@withLock true
                if (previous?.finished == true || (previous?.revision ?: -1) >= document.revision) {
                    return@withLock false
                }
                beforeDraftWrite()
                writeFile(file(sessionId), document)
                true
            }
        }

    @Keep
    private data class PendingSave(
        val oldKey: String?,
        val beforeJson: String?,
        val targetBeforeJson: String?,
        val plannedJson: String,
        val result: BookSourceEditDocument,
    )

    override suspend fun save(
        sessionId: String,
        document: BookSourceEditDocument,
        action: BookSourceSaveAction,
    ): BookSourceEditDocument =
        withContext(Dispatchers.IO) {
            saveLock(sessionId).withLock {
                readFile<PendingSave>(file(sessionId, journal = true))?.let { pending ->
                    return@withLock withContext(NonCancellable) { finishSave(sessionId, pending) }
                }
                check(!document.finished) { "书源编辑已关闭" }
                val previous = document.originalKey?.let(database.bookSourceDao::getBookSource)
                check(document.originalKey == null || previous != null) { "书源已被删除" }
                check(previous == null || sameContent(previous, document.original())) {
                    "书源已在其他页面更改，请重新打开编辑"
                }
                val source =
                    materializeBookSourceEditForm(
                        previous ?: document.original(),
                        document.form,
                        document.autoComplete,
                    )
                require(source.bookSourceUrl.isNotBlank() && source.bookSourceName.isNotBlank()) {
                    context.getString(R.string.non_null_name_url)
                }
                if (!sameContent(source, previous ?: document.original()))
                    source.lastUpdateTime = now()
                val target = database.bookSourceDao.getBookSource(source.bookSourceUrl)
                val normalized = normalizedForm(source, document.form)
                val result =
                    document.copy(
                        originalKey = source.bookSourceUrl,
                        savedUrl = source.bookSourceUrl,
                        originalJson = GSON.toJson(source),
                        form = normalized,
                        baseline = normalized,
                        revision = document.revision + 1,
                        delivery =
                            BookSourceSaveDelivery(
                                UUID.randomUUID().toString(),
                                action,
                                source.bookSourceUrl,
                            ),
                    )
                val pending =
                    PendingSave(
                        document.originalKey,
                        json(previous),
                        json(target),
                        GSON.toJson(source),
                        result,
                    )
                beforeJournalWrite()
                currentCoroutineContext().ensureActive()
                // Cancellation is accepted before persisting the fixed intent. Once accepted, Room
                // and the private receipt must finish together; failed receipts retain the journal.
                withContext(NonCancellable) {
                    writeFile(file(sessionId, journal = true), pending)
                    finishSave(sessionId, pending)
                }
            }
        }

    private suspend fun finishSave(
        sessionId: String,
        pending: PendingSave,
    ): BookSourceEditDocument {
        val source = GSON.fromJsonObject<BookSource>(pending.plannedJson).getOrThrow()
        val previous = pending.beforeJson?.let { GSON.fromJsonObject<BookSource>(it).getOrThrow() }
        database.runInTransaction {
            val current = pending.oldKey?.let(database.bookSourceDao::getBookSource)
            val target = database.bookSourceDao.getBookSource(source.bookSourceUrl)
            val committed =
                json(target) == pending.plannedJson &&
                    (pending.oldKey == null ||
                        pending.oldKey == source.bookSourceUrl ||
                        current == null)
            if (!committed) {
                check(
                    json(current) == pending.beforeJson && json(target) == pending.targetBeforeJson
                ) {
                    "书源在保存恢复期间已更改"
                }
                current?.let { old ->
                    database.bookSourceDao.delete(old)
                    if (old.bookSourceUrl != source.bookSourceUrl) {
                        database.cacheDao.deleteSourceVariables(old.bookSourceUrl)
                    }
                }
                database.bookSourceDao.insert(source)
            }
        }
        invalidate(previous, source)
        if (!writeDraft(sessionId, pending.result)) throw BookSourceDraftConflict()
        file(sessionId, journal = true).delete()
        return pending.result
    }

    override suspend fun importForm(text: String): BookSourceEditForm =
        withContext(Dispatchers.IO) {
            projectBookSourceEditForm(parseSource(text))
        }

    override suspend fun searchScope(sourceUrl: String): String =
        withContext(Dispatchers.IO) {
            val source = database.bookSourceDao.getBookSource(sourceUrl) ?: error("书源已被删除")
            "${source.bookSourceName.replace(":", "")}::${source.bookSourceUrl}"
        }

    override suspend fun parse(text: String): String =
        withContext(Dispatchers.IO) {
            GSON.toJson(parseSource(text))
        }

    private suspend fun parseSource(text: String): BookSource {
        return when {
            text.isAbsUrl() -> {
                val body = okHttpClient.newCallStrResponse { url(text) }.body
                parseSource(body ?: error("未获取到书源内容"))
            }
            text.isJsonArray() -> {
                if (text.contains("ruleSearchUrl") || text.contains("ruleFindUrl")) {
                    val items: List<Map<String, Any>> = jsonPath.parse(text).read("$")
                    ImportOldData.fromOldBookSource(jsonPath.parse(items.first()))
                } else {
                    GSON.fromJsonArray<BookSource>(text).getOrThrow().first()
                }
            }
            text.isJsonObject() -> {
                if (text.contains("ruleSearchUrl") || text.contains("ruleFindUrl")) {
                    ImportOldData.fromOldBookSource(jsonPath.parse(text))
                } else {
                    GSON.fromJsonObject<BookSource>(text).getOrThrow()
                }
            }
            else -> error("格式不对")
        }
    }

    override suspend fun export(document: BookSourceEditDocument): String =
        withContext(Dispatchers.IO) {
            GSON.toJson(document.source())
        }

    override suspend fun groups(): List<String> =
        withContext(Dispatchers.IO) { database.bookSourceDao.allGroups() }

    override suspend fun clearCookie(sourceUrl: String) =
        withContext(Dispatchers.IO) { CookieStore.removeCookie(sourceUrl) }

    override suspend fun variableComment(sourceUrl: String): String =
        withContext(Dispatchers.IO) {
            (database.bookSourceDao.getBookSource(sourceUrl) ?: BookSource())
                .getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
        }

    override suspend fun variable(sourceUrl: String): String =
        withContext(Dispatchers.IO) {
            database.bookSourceDao.getBookSource(sourceUrl)?.getVariable().orEmpty()
        }

    override suspend fun setVariable(sourceUrl: String, value: String?) =
        withContext(Dispatchers.IO) {
            database.bookSourceDao.getBookSource(sourceUrl)?.setVariable(value)
            Unit
        }

    override suspend fun transfer(text: String): String = transfers.write(text)

    override suspend fun editorText(path: String): String = transfers.read(path)

    override suspend fun release(vararg paths: String?) = transfers.delete(*paths)

    private fun json(source: BookSource?): String? = source?.let(GSON::toJson)

    private fun sameContent(source: BookSource, previous: BookSource): Boolean {
        // BookSource.equal fills missing rule objects through getters. Compare immutable
        // projections so checking content cannot alter the snapshots used by the journal.
        val sourceForm = projectBookSourceEditForm(source)
        val previousForm = projectBookSourceEditForm(previous)
        return sourceForm.options == previousForm.options &&
            sourceForm.tabs.map { fields -> fields.map { it.value } } ==
                previousForm.tabs.map { fields -> fields.map { it.value } } &&
            source.mainJs == previous.mainJs
    }

    private fun normalizedForm(source: BookSource, form: BookSourceEditForm): BookSourceEditForm {
        val normalized = projectBookSourceEditForm(source)
        return normalized.copy(
            tabs =
                normalized.tabs.mapIndexed { tab, fields ->
                    fields.map { field ->
                        val old = form.field(tab, field.key)
                        field
                            .copy(
                                selectionStart = old?.selectionStart ?: 0,
                                selectionEnd = old?.selectionEnd ?: 0,
                            )
                            .boundedSelection()
                    }
                }
        )
    }

    companion object {
        private val draftLocks = Array(64) { Mutex() }
        private val saveLocks = Array(64) { Mutex() }
    }
}

private suspend fun invalidateBookSource(previous: BookSource?, source: BookSource) {
    if (previous != null) {
        if (previous.exploreUrl != source.exploreUrl) previous.clearExploreKindsCache()
        if (previous.jsLib != source.jsLib) SharedJsScope.remove(previous.jsLib)
        if (previous.bookSourceUrl != source.bookSourceUrl) {
            previous.clearSharedGlobalState()
            AppCacheManager.clearSourceVariables()
        }
        SourceConfig.removeSource(previous.bookSourceUrl)
        concurrentRecordMap.remove(previous.bookSourceUrl)
    }
    concurrentRecordMap.remove(source.bookSourceUrl)
}
