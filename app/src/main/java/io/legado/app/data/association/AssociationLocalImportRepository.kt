package io.legado.app.data.association

import android.net.Uri
import android.util.AtomicFile
import androidx.annotation.Keep
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
import kotlinx.coroutines.NonCancellable
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
}

class LocalAssociationBookImportEngine : AssociationBookImportEngine {
    override fun current(bookUrl: String): Book? = appDb.bookDao.getBook(bookUrl)

    override fun import(uri: Uri, preview: Book): Book = LocalBook.importFile(uri, preview)
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
        withContext(NonCancellable) {
            require(runCatching { UUID.fromString(operationToken) }.isSuccess)
            sessions.withOwnedSession(ticket) { ownedDirectory, session ->
                check(session.operation?.token == operationToken) {
                    "Local import belongs to another request"
                }
                val previews = session.previews.filter { it.id in session.selectedIds }
                check(previews.isNotEmpty()) { "No books selected" }
                val file = AtomicFile(File(ownedDirectory, "local-import-$operationToken.json"))
                var journal =
                    readJournal(file)
                        ?: AssociationLocalImportJournal(previews.map { it.id }, directoryUri)
                            .also { writeJournal(file, it) }
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
                            writeJournal(file, journal)
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
                        writeJournal(file, updated)
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
                    try {
                        val preview = GSON.fromJsonObject<Book>(copy.bookJson).getOrThrow()
                        val existing = engine.current(preview.bookUrl)
                        check(!copy.imported || existing != null) { "An imported book was deleted" }
                        // A failed receipt write after Room acceptance is recovered by this exact
                        // reserved URL. Never rerun importFile on an existing reading-position row.
                        val book =
                            existing ?: engine.import(Uri.parse(copy.destinationUri), preview)
                        if (!copy.imported) {
                            val updated =
                                journal.copy(
                                    copies =
                                        journal.copies.map {
                                            if (it.previewId == copy.previewId)
                                                it.copy(imported = true)
                                            else it
                                        }
                                )
                            writeJournal(file, updated)
                            journal = updated
                        }
                        books += GSON.toJson(book)
                    } catch (failure: Throwable) {
                        if (firstFailure == null) firstFailure = failure
                        AppLog.put("导入分享书籍失败\n${failure.localizedMessage}", failure)
                    }
                }
                if (books.isEmpty())
                    throw firstFailure ?: IllegalStateException("No books could be imported")
                journal = journal.copy(completed = true)
                writeJournal(file, journal)
                AssociationLocalImportResult(previews.size, books)
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
