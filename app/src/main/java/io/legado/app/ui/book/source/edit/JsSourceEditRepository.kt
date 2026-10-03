package io.legado.app.ui.book.source.edit

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.FileCodeDialogTransferRepository
import io.legado.app.model.jsSource.JsSourceUpsert
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class JsSourceDraft(
    val text: String,
    val sourceUrl: String?,
    val stage: JsSourceEditStage,
    val editorPath: String? = null,
    val ownedTransfers: List<String> = emptyList(),
    val editorReturning: Boolean = false,
    val returnedText: String? = null,
    val returnedPath: String? = null,
    val returnedAction: String? = null,
    val finished: Boolean = false,
    val saved: Boolean = false,
    val missingLogin: Boolean = false,
    val successToast: Boolean = false,
    val revision: Long = 0,
)

internal interface JsSourceEditRepository {
    suspend fun read(sessionId: String): JsSourceDraft?

    suspend fun write(sessionId: String, draft: JsSourceDraft)

    suspend fun initial(sourceUrl: String?): String

    suspend fun save(
        text: String,
        sourceUrl: String?,
        onAccepted: suspend (BookSource) -> Unit,
    ): BookSource

    suspend fun editorText(path: String): String

    suspend fun transfer(text: String): String

    suspend fun release(path: String?)
}

internal class FileJsSourceEditRepository(context: Context) : JsSourceEditRepository {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, "js-source-edit-drafts")
    private val transfers = FileCodeDialogTransferRepository(this.context)

    private fun file(sessionId: String): AtomicFile {
        UUID.fromString(sessionId)
        check(directory.isDirectory || directory.mkdirs()) { "无法保存书源草稿" }
        return AtomicFile(File(directory, "$sessionId.json"))
    }

    private fun lock(sessionId: String): Mutex {
        val path = File(directory, "$sessionId.json").absolutePath
        return locks.getOrPut(path) { Mutex() }
    }

    private fun readFile(sessionId: String): JsSourceDraft? {
        val input =
            try {
                file(sessionId).openRead()
            } catch (_: java.io.FileNotFoundException) {
                return null
            }
        return GSON.fromJsonObject<JsSourceDraft>(input.bufferedReader().use { it.readText() })
            .getOrThrow()
    }

    override suspend fun read(sessionId: String): JsSourceDraft? =
        withContext(Dispatchers.IO) {
            lock(sessionId).withLock { readFile(sessionId) }
        }

    override suspend fun write(sessionId: String, draft: JsSourceDraft) {
        // A committed close is a tombstone: old writers cannot revive executable source text.
        withContext(Dispatchers.IO + NonCancellable) {
            lock(sessionId).withLock {
                val previousDraft = readFile(sessionId)
                if (
                    previousDraft?.finished == true ||
                        (previousDraft?.revision ?: -1) > draft.revision
                ) {
                    return@withLock
                }
                val target = file(sessionId)
                val output = target.startWrite()
                try {
                    output.write(GSON.toJson(draft).toByteArray(Charsets.UTF_8))
                    target.finishWrite(output)
                } catch (error: Throwable) {
                    target.failWrite(output)
                    throw error
                }
            }
        }
    }

    override suspend fun initial(sourceUrl: String?): String =
        withContext(Dispatchers.IO) {
            sourceUrl?.let { appDb.bookSourceDao.getBookSource(it)?.mainJs }
                ?: context.assets.open("js_source_template.js").bufferedReader().use {
                    it.readText()
                }
        }

    override suspend fun save(
        text: String,
        sourceUrl: String?,
        onAccepted: suspend (BookSource) -> Unit,
    ): BookSource =
        withContext(Dispatchers.IO) {
            JsSourceUpsert.save(text, sourceUrl, onAccepted = onAccepted)
        }

    override suspend fun editorText(path: String): String = transfers.read(path)

    override suspend fun transfer(text: String): String = transfers.write(text)

    override suspend fun release(path: String?) = transfers.delete(path)

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
