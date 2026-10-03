package io.legado.app.data.association

import android.net.Uri
import android.util.AtomicFile
import androidx.annotation.Keep
import androidx.room.withTransaction
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.delete
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Keep
data class AssociationCopiedBook(
    val previewId: String,
    val destinationUri: String,
    val bookJson: String,
    val imported: Boolean = false,
)

@Keep
data class AssociationLocalImportJournal(
    val selectedIds: List<String>,
    val directoryUri: String,
    val copies: List<AssociationCopiedBook> = emptyList(),
    val failedIds: List<String> = emptyList(),
    val completed: Boolean = false,
)

data class AssociationLocalImportResult(val selectedCount: Int, val bookJson: List<String>)

interface AssociationBookImportEngine {
    fun current(bookUrl: String): Book?

    fun import(uri: Uri, preview: Book): Book

    suspend fun accept(uri: Uri, preview: Book, onAccepted: suspend (Book) -> Unit): Book {
        val book = current(preview.bookUrl) ?: import(uri, preview)
        onAccepted(book)
        return book
    }
}

class LocalAssociationBookImportEngine : AssociationBookImportEngine {
    override fun current(bookUrl: String): Book? = appDb.bookDao.getBook(bookUrl)

    override fun import(uri: Uri, preview: Book): Book = LocalBook.importFile(uri, preview)

    override suspend fun accept(
        uri: Uri,
        preview: Book,
        onAccepted: suspend (Book) -> Unit,
    ): Book = appDb.withTransaction {
        // Metadata is already parsed and copied. Only the short Room insert and private receipt
        // run here, in Room -> session-file order; no extraction or stream copy holds SQL locks.
        val book = current(preview.bookUrl) ?: import(uri, preview)
        onAccepted(book)
        book
    }
}

/**
 * Accepted local copies are journaled before Room import so restored owners reuse the same files.
 */
class AssociationLocalImportRepository(
    private val sessions: FileAssociationSessionRepository,
    private val engine: AssociationBookImportEngine = LocalAssociationBookImportEngine(),
    private val beforeJournalWrite: (AssociationLocalImportJournal) -> Unit = {},
) {
    suspend fun import(
        ticket: String,
        operationToken: String,
        directoryUri: String,
    ): AssociationLocalImportResult =
        withContext(Dispatchers.IO + NonCancellable) {
            require(runCatching { UUID.fromString(operationToken) }.isSuccess)
            val (ownedDirectory, session) =
                sessions.withOwnedSession(ticket) { directory, current ->
                    directory to current
                }
            // Recreated owners share this operation gate, independently of the short file gate.
            // Release never waits here, and no command acquires these locks in reverse order.
            val operationLock =
                operationLocks[
                    Math.floorMod(ownedDirectory.canonicalPath.hashCode(), operationLocks.size)]
            operationLock.withLock {
                check(session.operation?.token == operationToken) {
                    "Local import belongs to another request"
                }
                val previews = session.previews.filter { it.id in session.selectedIds }
                check(previews.isNotEmpty()) { "No books selected" }
                val file = AtomicFile(File(ownedDirectory, "local-import-$operationToken.json"))
                var journal =
                    sessions.withOwnedSession(ticket) { _, _ -> readJournal(file) }
                        ?: AssociationLocalImportJournal(previews.map { it.id }, directoryUri)
                            .also { writeOwnedJournal(ticket, operationToken, it) }
                check(
                    journal.selectedIds == previews.map { it.id } &&
                        journal.directoryUri == directoryUri
                ) {
                    "Local import selection changed"
                }
                for (preview in previews) {
                    if (
                        journal.copies.any { it.previewId == preview.id } ||
                            preview.id in journal.failedIds
                    )
                        continue
                    val source = FileDoc.fromUri(Uri.parse(preview.fileUri), false)
                    val copied =
                        try {
                            copyAssociationLocalBook(source, Uri.parse(directoryUri))
                        } catch (failure: Throwable) {
                            AppLog.put("复制分享书籍失败\n${failure.localizedMessage}", failure)
                            journal = journal.copy(failedIds = journal.failedIds + preview.id)
                            writeOwnedJournal(ticket, operationToken, journal)
                            continue
                        }
                    var destinationCover: File? = null
                    try {
                        val original = GSON.fromJsonObject<Book>(preview.bookJson).getOrThrow()
                        val book =
                            original.copy(bookUrl = FileDoc.fromUri(copied, false).toString())
                        original.coverUrl?.let { coverPath ->
                            val cover = File(coverPath)
                            if (coverPath == LocalBook.getCoverPath(original) && cover.isFile) {
                                val target = File(LocalBook.getCoverPath(book))
                                cover.copyTo(target, overwrite = true)
                                destinationCover = target
                                book.coverUrl = target.path
                            }
                        }
                        val record =
                            AssociationCopiedBook(preview.id, copied.toString(), GSON.toJson(book))
                        val updated = journal.copy(copies = journal.copies + record)
                        writeOwnedJournal(ticket, operationToken, updated)
                        journal = updated
                    } catch (failure: Throwable) {
                        // Room has not seen this newly reserved destination. Only our own copy can
                        // be removed here; collision checks never select an existing sender file.
                        FileDoc.fromUri(copied, false).delete()
                        destinationCover?.delete()
                        throw failure
                    }
                }
                val books = mutableListOf<String>()
                var firstFailure: Throwable? = null
                for (copy in journal.copies) {
                    var receiptStarted = false
                    val book =
                        try {
                            val preview = GSON.fromJsonObject<Book>(copy.bookJson).getOrThrow()
                            val existing = engine.current(preview.bookUrl)
                            check(!copy.imported || existing != null) {
                                "The accepted result cannot be verified; close and confirm a new import"
                            }
                            if (copy.imported) checkNotNull(existing)
                            else
                                engine.accept(Uri.parse(copy.destinationUri), preview) {
                                    receiptStarted = true
                                    val updated =
                                        journal.copy(
                                            copies =
                                                journal.copies.map {
                                                    if (it.previewId == copy.previewId)
                                                        it.copy(imported = true)
                                                    else it
                                                }
                                        )
                                    // This short file gate is acquired after Room. A closed owner
                                    // rejects
                                    // acceptance and rolls back SQL, rather than inserting after
                                    // close.
                                    writeOwnedJournal(ticket, operationToken, updated)
                                    journal = updated
                                }
                        } catch (failure: Throwable) {
                            // A receipt or post-receipt SQL failure is uncertain, not a rejected
                            // book.
                            // Stop the batch; restoration verifies accepted rows without
                            // recreating.
                            if (receiptStarted || copy.imported) throw failure
                            if (firstFailure == null) firstFailure = failure
                            AppLog.put("导入分享书籍失败\n${failure.localizedMessage}", failure)
                            continue
                        }
                    books += GSON.toJson(book)
                }
                if (books.isEmpty())
                    throw firstFailure ?: IllegalStateException("No books could be imported")
                journal = journal.copy(completed = true)
                writeOwnedJournal(ticket, operationToken, journal)
                AssociationLocalImportResult(previews.size, books)
            }
        }

    private companion object {
        val operationLocks = Array(64) { Mutex() }
    }

    private suspend fun writeOwnedJournal(
        ticket: String,
        operationToken: String,
        journal: AssociationLocalImportJournal,
    ) {
        sessions.withOwnedSession(ticket) { directory, session ->
            check(session.operation?.token == operationToken) {
                "Local import belongs to another request"
            }
            writeJournal(AtomicFile(File(directory, "local-import-$operationToken.json")), journal)
        }
    }

    private fun readJournal(file: AtomicFile): AssociationLocalImportJournal? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val journal =
            file.openRead().bufferedReader().use {
                GSON.fromJsonObject<AssociationLocalImportJournal>(it.readText()).getOrThrow()
            }
        requireNotNull(journal.selectedIds)
        requireNotNull(journal.directoryUri)
        requireNotNull(journal.copies)
        requireNotNull(journal.failedIds)
        return journal
    }

    private fun writeJournal(file: AtomicFile, journal: AssociationLocalImportJournal) {
        beforeJournalWrite(journal)
        val output = file.startWrite()
        try {
            output.write(GSON.toJson(journal).toByteArray())
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
}
