package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.association.AssociationFileInspection
import io.legado.app.data.association.AssociationFileRepository
import io.legado.app.data.association.AssociationImportOperations
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationOnlinePayload
import io.legado.app.data.association.AssociationOnlineRepository
import io.legado.app.data.association.AssociationOperation
import io.legado.app.data.association.AssociationOperationResult
import io.legado.app.data.association.AssociationSession
import io.legado.app.data.association.AssociationSessionRepository
import io.legado.app.ui.association.compose.AssociationImportViewModel

/** In-memory boundary for actual state-machine behavior; no source text is inspected. */
internal class AssociationStateFixture(
    initial: AssociationSession,
    inspect: suspend () -> AssociationFileInspection = { error("Inspection not expected") },
    mutate: suspend (AssociationOperation) -> AssociationOperationResult = {
        AssociationOperationResult()
    },
) {
    val sessions = Sessions(initial)
    val savedState =
        SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "owned-ticket"))
    var acceptedMutations = 0
        private set

    val model =
        AssociationImportViewModel(
            savedState,
            sessions,
            object : AssociationFileRepository {
                override suspend fun inspect(ticket: String, input: AssociationInput) = inspect()
            },
            object : AssociationOnlineRepository {
                override suspend fun determine(
                    ticket: String,
                    url: String,
                ): AssociationOnlinePayload = error("Network not expected")

                override suspend fun readConfig(
                    ticket: String,
                    url: String,
                ): AssociationOnlinePayload = error("Network not expected")

                override suspend fun text(url: String): String = error("Network not expected")
            },
            object : AssociationImportOperations {
                override suspend fun execute(
                    ticket: String,
                    session: AssociationSession,
                    operation: AssociationOperation,
                ): AssociationOperationResult {
                    acceptedMutations++
                    return mutate(operation)
                }
            },
        )
    private val store = ViewModelStore().apply { put("model", model) }

    fun close() = store.clear()

    class Sessions(var current: AssociationSession) : AssociationSessionRepository {
        override suspend fun create(input: AssociationInput): String =
            error("Restoration must not allocate another session")

        override suspend fun read(ticket: String): AssociationSession = current

        override suspend fun write(ticket: String, value: AssociationSession): Boolean {
            if (value.revision <= current.revision) return false
            current = value
            return true
        }

        override suspend fun writeBytes(ticket: String, name: String, bytes: ByteArray): Unit =
            error("Unused byte write")

        override suspend fun readBytes(ticket: String, name: String): ByteArray =
            error("Unused byte read")

        override suspend fun release(ticket: String) = Unit
    }
}
