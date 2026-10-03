package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.AppCacheManager
import io.legado.app.help.ConcurrentRateLimiter.Companion.concurrentRecordMap
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.clearSharedGlobalState
import io.legado.app.help.source.removeSortCache
import io.legado.app.model.SharedJsScope
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface RssSourceEditorRepository {
    suspend fun load(key: String): RssSourceEditorDocument?

    suspend fun readDraft(session: String): RssSourceEditorDocument?

    suspend fun writeDraft(session: String, document: RssSourceEditorDocument)

    suspend fun save(
        session: String,
        document: RssSourceEditorDocument,
        action: RssSourceEditorSaveAction,
        autoComplete: Boolean,
    ): RssSourceEditorDocument

    suspend fun parse(text: String): RssSourceEditorDraft?

    suspend fun export(document: RssSourceEditorDocument, autoComplete: Boolean): String

    suspend fun clearCookie(url: String)

    suspend fun variable(key: String): String?

    suspend fun setVariable(key: String, value: String?)

    suspend fun editorInput(text: String): String

    suspend fun editorText(path: String): String

    suspend fun clearEditor(vararg paths: String?)
}

class RoomRssSourceEditorRepository(
    context: Context,
    private val database: AppDatabase = appDb,
    private val directory: File = File(context.applicationContext.filesDir, "rss-source-editor"),
    private val transfer: CodeDialogTransferRepository =
        FileCodeDialogTransferRepository(context.applicationContext),
    private val now: () -> Long = System::currentTimeMillis,
    private val beforeJournalWrite: suspend () -> Unit = {},
    private val beforeDraftWrite: suspend () -> Unit = {},
    private val invalidate: suspend (RssSource?, RssSource) -> Unit = { old, source ->
        if (old != null && old.sortUrl != source.sortUrl) old.removeSortCache()
        if (old != null && old.jsLib != source.jsLib) SharedJsScope.remove(old.jsLib)
        if (old != null && old.sourceUrl != source.sourceUrl) {
            old.clearSharedGlobalState()
            AppCacheManager.clearSourceVariables()
        }
        concurrentRecordMap.remove(source.sourceUrl)
    },
) : RssSourceEditorRepository {
    override suspend fun load(key: String) =
        withContext(Dispatchers.IO) {
            database.rssSourceDao.getByKey(key)?.let {
                RssSourceEditorDocument(
                    key,
                    RssSourceEditorDraft.from(it),
                    lastUpdateTime = it.lastUpdateTime,
                    customOrder = it.customOrder,
                )
            }
        }

    private fun file(session: String): AtomicFile {
        require(runCatching { UUID.fromString(session) }.isSuccess) { "Invalid draft session" }
        return AtomicFile(File(directory, "$session.json"))
    }

    private fun read(session: String): RssSourceEditorDocument? {
        val file = file(session)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use {
            GSON.fromJsonObject<RssSourceEditorDocument>(it.readText()).getOrThrow()
        }
    }

    override suspend fun readDraft(session: String) =
        withContext(Dispatchers.IO + NonCancellable) {
            saveLock(session).withLock {
                pending(session)?.let { finishSave(session, it) }
                lock(session).withLock { read(session) }
            }
        }

    override suspend fun writeDraft(session: String, document: RssSourceEditorDocument) =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
                if ((read(session)?.revision ?: -1) <= document.revision) {
                    beforeDraftWrite()
                    directory.mkdirs()
                    val file = file(session)
                    val output = file.startWrite()
                    try {
                        output.write(GSON.toJson(document).toByteArray())
                        file.finishWrite(output)
                    } catch (error: Throwable) {
                        file.failWrite(output)
                        throw error
                    }
                }
            }
        }

    private data class PendingSave(
        val oldKey: String?,
        val before: String?,
        val targetBefore: String?,
        val planned: String,
        val result: RssSourceEditorDocument,
    )

    private fun journal(session: String): AtomicFile {
        file(session) // Validate the session before deriving another path.
        return AtomicFile(File(directory, "$session-save.json"))
    }

    private fun pending(session: String): PendingSave? {
        val journal = journal(session)
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists())
            return null
        return journal.openRead().bufferedReader().use {
            GSON.fromJsonObject<PendingSave>(it.readText()).getOrThrow()
        }
    }

    private suspend fun writeJournal(session: String, pending: PendingSave) {
        beforeJournalWrite()
        directory.mkdirs()
        val file = journal(session)
        val output = file.startWrite()
        try {
            output.write(GSON.toJson(pending).toByteArray())
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun json(source: RssSource?) = source?.let { GSON.toJson(it) }

    private suspend fun finishSave(session: String, pending: PendingSave): RssSourceEditorDocument {
        val planned = GSON.fromJsonObject<RssSource>(pending.planned).getOrThrow()
        val previous = pending.before?.let { GSON.fromJsonObject<RssSource>(it).getOrThrow() }
        database.runInTransaction {
            val old = pending.oldKey?.let(database.rssSourceDao::getByKey)
            val target = database.rssSourceDao.getByKey(planned.sourceUrl)
            val committed =
                json(target) == pending.planned &&
                    (pending.oldKey == null || pending.oldKey == planned.sourceUrl || old == null)
            if (!committed) {
                // A recovery must never overwrite a concurrent edit or recreate a deleted original.
                check(json(old) == pending.before && json(target) == pending.targetBefore) {
                    "Source changed during save recovery"
                }
                if (old != null) {
                    database.rssSourceDao.delete(old)
                    if (old.sourceUrl != planned.sourceUrl) {
                        database.rssStarDao.updateOrigin(planned.sourceUrl, old.sourceUrl)
                        database.rssArticleDao.updateOrigin(planned.sourceUrl, old.sourceUrl)
                        database.cacheDao.deleteSourceVariables(old.sourceUrl)
                    }
                }
                database.rssSourceDao.insert(planned)
            }
        }
        invalidate(previous, planned)
        writeDraft(session, pending.result)
        journal(session).delete()
        return pending.result
    }

    override suspend fun save(
        session: String,
        document: RssSourceEditorDocument,
        action: RssSourceEditorSaveAction,
        autoComplete: Boolean,
    ) =
        withContext(Dispatchers.IO + NonCancellable) {
            saveLock(session).withLock {
                pending(session)?.let {
                    return@withLock finishSave(session, it)
                }
                require(document.draft.valid()) { "Name and URL are required" }
                val previous = document.originalKey?.let(database.rssSourceDao::getByKey)
                check(document.originalKey == null || previous != null) {
                    "Source no longer exists"
                }
                val base =
                    previous
                        ?: RssSource(
                            customOrder = document.customOrder,
                            lastUpdateTime = document.lastUpdateTime,
                        )
                val source = document.draft.entity(base, autoComplete)
                if (!RssSourceEditorDraft.from(base).sameContent(RssSourceEditorDraft.from(source)))
                    source.lastUpdateTime = now()
                val target = database.rssSourceDao.getByKey(source.sourceUrl)
                val normalized = RssSourceEditorDraft.from(source)
                val draft =
                    normalized.copy(
                        fields =
                            normalized.fields.mapValues { (field, text) ->
                                text
                                    .copy(
                                        start = document.draft[field].start,
                                        end = document.draft[field].end,
                                    )
                                    .bounded()
                            }
                    )
                val result =
                    document.copy(
                        originalKey = source.sourceUrl,
                        draft = draft,
                        baseline = draft,
                        customOrder = source.customOrder,
                        lastUpdateTime = source.lastUpdateTime,
                        revision = document.revision + 1,
                        delivery =
                            RssSourceEditorDelivery(
                                UUID.randomUUID().toString(),
                                action,
                                source.sourceUrl,
                                !source.loginUrl.isNullOrBlank(),
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
                writeJournal(session, pending) // No Room mutation is allowed before this succeeds.
                try {
                    finishSave(session, pending)
                } catch (error: Throwable) {
                    // A rolled-back transaction can discard its intent. A committed transaction
                    // must retain the recovery receipt.
                    val current = database.rssSourceDao.getByKey(source.sourceUrl)
                    if (
                        json(current) != pending.planned &&
                            json(document.originalKey?.let(database.rssSourceDao::getByKey)) ==
                                pending.before &&
                            json(current) == pending.targetBefore
                    )
                        journal(session).delete()
                    throw error
                }
            }
        }

    override suspend fun parse(text: String) =
        withContext(Dispatchers.Default) {
            (GSON.fromJsonObject<RssSource>(text.trim()).getOrNull()
                    ?: GSON.fromJsonArray<RssSource>(text.trim()).getOrNull()?.singleOrNull())
                ?.let(RssSourceEditorDraft::from)
        }

    override suspend fun export(document: RssSourceEditorDocument, autoComplete: Boolean) =
        withContext(Dispatchers.Default) {
            GSON.toJson(
                document.draft.entity(
                    RssSource(
                        lastUpdateTime = document.lastUpdateTime,
                        customOrder = document.customOrder,
                    ),
                    autoComplete,
                )
            )
        }

    override suspend fun clearCookie(url: String) =
        withContext(Dispatchers.IO) { CookieStore.removeCookie(url) }

    override suspend fun variable(key: String) =
        withContext(Dispatchers.IO) { database.rssSourceDao.getByKey(key)?.getVariable() }

    override suspend fun setVariable(key: String, value: String?) =
        withContext(Dispatchers.IO) {
            database.rssSourceDao.getByKey(key)?.setVariable(value)
            Unit
        }

    override suspend fun editorInput(text: String) = transfer.write(text)

    override suspend fun editorText(path: String) = transfer.read(path)

    override suspend fun clearEditor(vararg paths: String?) = transfer.delete(*paths)

    private fun lock(session: String) =
        locks.getOrPut(File(directory, session).absolutePath) { Mutex() }

    private fun saveLock(session: String) =
        saveLocks.getOrPut(File(directory, session).absolutePath) { Mutex() }

    companion object {
        private val saveLocks = ConcurrentHashMap<String, Mutex>()
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
