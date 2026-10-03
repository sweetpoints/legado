package io.legado.app.data.association

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.importBookshelfJson
import io.legado.app.data.repository.parseBookshelfImport
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.readText
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@Keep
data class AssociationBookshelfImportEntry(
    val name: String,
    val author: String,
    val bookUrl: String? = null,
    val accepted: Boolean = false,
    val previouslyPresent: Boolean = false,
)

@Keep
data class AssociationBookshelfImportJournal(
    val source: String,
    val entries: List<AssociationBookshelfImportEntry>,
    val completed: Boolean = false,
)

interface AssociationBookshelfImportEngine {
    fun existing(name: String, author: String): Book?

    fun current(bookUrl: String): Book?

    suspend fun import(json: String, accepted: suspend (String, String, String) -> Unit)
}

class LegacyAssociationBookshelfImportEngine : AssociationBookshelfImportEngine {
    override fun existing(name: String, author: String): Book? = appDb.bookDao.getBook(name, author)

    override fun current(bookUrl: String): Book? = appDb.bookDao.getBook(bookUrl)

    override suspend fun import(json: String, accepted: suspend (String, String, String) -> Unit) {
        importBookshelfJson(json, 0, accepted)
    }
}

/**
 * A per-book Room observer records acceptance so replay never searches for an externally deleted
 * row.
 */
class AssociationBookshelfImportRepository(
    context: Context,
    private val sessions: FileAssociationSessionRepository,
    private val engine: AssociationBookshelfImportEngine = LegacyAssociationBookshelfImportEngine(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeJournalWrite: (AssociationBookshelfImportJournal) -> Unit = {},
) {
    private val applicationContext = context.applicationContext

    suspend fun import(ticket: String, operationToken: String, source: String): Int =
        withContext(dispatcher) {
            require(runCatching { UUID.fromString(operationToken) }.isSuccess)
            val json = Uri.parse(source).readText(applicationContext)
            val requested = parseBookshelfImport(json)
            // Room reads stay outside the private-session gate. The accepted observer holds a Room
            // transaction while writing its file receipt, so the reverse lock order would deadlock.
            val baseline = requested.map { (name, author) ->
                val existing = engine.existing(name, author)
                AssociationBookshelfImportEntry(
                    name,
                    author,
                    existing?.bookUrl,
                    accepted = existing != null,
                    previouslyPresent = existing != null,
                )
            }
            val journal =
                sessions.withOwnedSession(ticket) { directory, session ->
                    requireOwner(session, operationToken, source)
                    val file = journalFile(directory, operationToken)
                    readJournal(file)
                        ?: AssociationBookshelfImportJournal(source, baseline).also {
                            writeJournal(file, it)
                        }
                }
            check(
                journal.source == source &&
                    journal.entries.map { it.name to it.author } == requested
            ) {
                "Bookshelf import request changed"
            }
            for (entry in journal.entries.filter { it.accepted }) {
                check(engine.current(checkNotNull(entry.bookUrl)) != null) {
                    "The accepted result cannot be verified; close and confirm a new import"
                }
            }
            if (journal.completed) return@withContext journal.entries.size
            val pending = journal.entries.filterNot { it.accepted }
            if (pending.isNotEmpty()) {
                val pendingJson =
                    GSON.toJson(pending.map { mapOf("name" to it.name, "author" to it.author) })
                engine.import(pendingJson) { name, author, url ->
                    // The shared producer invokes this inside the actual accepted Room transaction.
                    // This file-only lease cannot query Room or wait on a remote request.
                    sessions.withOwnedSession(ticket) { directory, session ->
                        requireOwner(session, operationToken, source)
                        val file = journalFile(directory, operationToken)
                        val latest = checkNotNull(readJournal(file))
                        check(latest.entries.any { it.name == name && it.author == author }) {
                            "Unknown accepted bookshelf record"
                        }
                        val updated =
                            latest.copy(
                                entries =
                                    latest.entries.map { entry ->
                                        if (entry.name == name && entry.author == author)
                                            entry.copy(bookUrl = url, accepted = true)
                                        else entry
                                    }
                            )
                        writeJournal(file, updated)
                    }
                }
            }
            withContext(NonCancellable) {
                    sessions.withOwnedSession(ticket) { directory, session ->
                        requireOwner(session, operationToken, source)
                        val file = journalFile(directory, operationToken)
                        val latest = checkNotNull(readJournal(file))
                        // A row added by another host can be skipped by the legacy engine after
                        // baseline.
                        // Resolve those records outside this gate below; never fabricate acceptance
                        // here.
                        latest
                    }
                }
                .let { latest ->
                    val reconciled =
                        latest.entries.map { entry ->
                            if (entry.accepted) entry
                            else {
                                val existing = engine.existing(entry.name, entry.author)
                                checkNotNull(existing) {
                                    "Bookshelf import has an unresolved record"
                                }
                                entry.copy(
                                    bookUrl = existing.bookUrl,
                                    accepted = true,
                                    previouslyPresent = true,
                                )
                            }
                        }
                    withContext(NonCancellable) {
                        sessions.withOwnedSession(ticket) { directory, session ->
                            requireOwner(session, operationToken, source)
                            val file = journalFile(directory, operationToken)
                            val current = checkNotNull(readJournal(file))
                            val completed =
                                current.copy(
                                    entries =
                                        current.entries.map { entry ->
                                            if (entry.accepted) entry
                                            else
                                                reconciled.first {
                                                    it.name == entry.name &&
                                                        it.author == entry.author
                                                }
                                        },
                                    completed = true,
                                )
                            writeJournal(file, completed)
                            completed.entries.size
                        }
                    }
                }
        }

    private fun requireOwner(session: AssociationSession, token: String, source: String) {
        check(session.operation?.token == token && session.importSource == source) {
            "Bookshelf import belongs to another request"
        }
    }

    private fun journalFile(directory: File, token: String) =
        AtomicFile(File(directory, "bookshelf-import-$token.json"))

    private fun readJournal(file: AtomicFile): AssociationBookshelfImportJournal? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val journal =
            file.openRead().bufferedReader().use {
                GSON.fromJsonObject<AssociationBookshelfImportJournal>(it.readText()).getOrThrow()
            }
        requireNotNull(journal.source)
        requireNotNull(journal.entries)
        journal.entries.forEach { entry ->
            requireNotNull(entry.name)
            requireNotNull(entry.author)
            if (entry.accepted) requireNotNull(entry.bookUrl)
        }
        return journal
    }

    private fun writeJournal(file: AtomicFile, journal: AssociationBookshelfImportJournal) {
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
