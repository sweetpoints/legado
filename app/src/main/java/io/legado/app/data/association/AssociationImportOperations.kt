package io.legado.app.data.association

import android.content.Context
import android.net.Uri
import io.legado.app.data.entities.Book
import io.legado.app.help.storage.Restore
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AssociationOperationResult(
    val bookJson: String? = null,
    val message: String? = null,
    val importedCount: Int? = null,
    val selectedCount: Int? = null,
)

interface AssociationImportOperations {
    suspend fun execute(
        ticket: String,
        session: AssociationSession,
        operation: AssociationOperation,
    ): AssociationOperationResult
}

/** User-confirmed mutations use their existing engines, independently of native UI delivery. */
class LegacyAssociationImportOperations(
    context: Context,
    private val sessions: FileAssociationSessionRepository,
) : AssociationImportOperations {
    private val applicationContext = context.applicationContext

    override suspend fun execute(
        ticket: String,
        session: AssociationSession,
        operation: AssociationOperation,
    ): AssociationOperationResult =
        withContext(Dispatchers.IO) {
            when (operation.kind) {
                "local-import" -> {
                    val result =
                        AssociationLocalImportRepository(sessions)
                            .import(
                                ticket,
                                operation.token,
                                checkNotNull(operation.payload),
                            )
                    AssociationOperationResult(
                        bookJson = if (session.openSingleBook) result.bookJson.single() else null,
                        importedCount = result.bookJson.size,
                        selectedCount = result.selectedCount,
                    )
                }
                "bookshelf" -> {
                    val count =
                        AssociationBookshelfImportRepository(applicationContext, sessions)
                            .import(ticket, operation.token, checkNotNull(session.importSource))
                    AssociationOperationResult(importedCount = count)
                }
                "read-config" ->
                    AssociationOperationResult(
                        message =
                            AssociationReadConfigRepository(sessions)
                                .import(ticket, operation.token)
                    )
                "backup" -> {
                    // Restoring many independent stores is not replay-safe. The durable accepted
                    // marker precedes this call; interruption requires new human confirmation.
                    Restore.restoreOrThrow(
                        applicationContext,
                        Uri.parse(checkNotNull(session.importSource)),
                    )
                    AssociationOperationResult()
                }
                "unsupported" -> {
                    val book: Book =
                        LocalBook.importFile(Uri.parse(checkNotNull(session.unsupportedUri)))
                    AssociationOperationResult(bookJson = GSON.toJson(book))
                }
                else -> error("Unsupported import operation")
            }
        }
}
