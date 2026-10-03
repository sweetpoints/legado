package io.legado.app.data.association

import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Platform callbacks carry this owner token; their URI bodies are persisted privately. */
@Keep
data class AssociationNativeResult(
    val receipt: AssociationNativeReceipt,
    val directory: String? = null,
    val permissionGranted: Boolean? = null,
) {
    init {
        require(
            receipt.kind == AssociationNativeKind.SelectDirectory ||
                receipt.kind == AssociationNativeKind.StoragePermission
        )
        require(
            receipt.kind != AssociationNativeKind.StoragePermission || permissionGranted != null
        )
    }
}

interface AssociationNativeResultRepository {
    suspend fun record(ticket: String, result: AssociationNativeResult): Boolean

    suspend fun read(ticket: String): List<AssociationNativeResult>

    suspend fun acknowledge(ticket: String, receipt: AssociationNativeReceipt)
}

/**
 * A callback may arrive before the recreated VM has read its session. Keep it beside that session
 * under the same close fence, without reading Activity state or putting a directory URI in Bundle.
 * A result remains until its corresponding command is durably accepted; release removes both.
 */
class FileAssociationNativeResultRepository(
    private val sessions: FileAssociationSessionRepository
) : AssociationNativeResultRepository {
    override suspend fun record(ticket: String, result: AssociationNativeResult): Boolean =
        withContext(NonCancellable) {
            sessions.withOwnedSession(ticket) { directory, session ->
                if (
                    result.receipt.generation != session.generation ||
                        result.receipt !in session.claimedEffects
                )
                    return@withOwnedSession false
                val accepted = readRecords(directory)
                // First delivery wins. A duplicate or contradictory callback cannot replace it.
                if (accepted.any { it.receipt.token == result.receipt.token }) {
                    return@withOwnedSession false
                }
                writeRecords(directory, accepted + result)
                true
            }
        }

    override suspend fun read(ticket: String): List<AssociationNativeResult> =
        sessions.withOwnedSession(ticket) { directory, session ->
            readRecords(directory).filter { it.receipt.generation == session.generation }
        }

    override suspend fun acknowledge(ticket: String, receipt: AssociationNativeReceipt) {
        withContext(NonCancellable) {
            sessions.withOwnedSession(ticket) { directory, session ->
                if (receipt.generation != session.generation) return@withOwnedSession
                val accepted = readRecords(directory)
                val remaining = accepted.filterNot { it.receipt == receipt }
                if (remaining.size != accepted.size) writeRecords(directory, remaining)
            }
        }
    }

    private fun file(directory: File) = AtomicFile(File(directory, "native-results.json"))

    private fun readRecords(directory: File): List<AssociationNativeResult> {
        val file = file(directory)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return emptyList()
        }
        return file.openRead().bufferedReader().use {
            GSON.fromJsonObject<Array<AssociationNativeResult>>(it.readText()).getOrThrow().toList()
        }
    }

    private fun writeRecords(directory: File, records: List<AssociationNativeResult>) {
        val file = file(directory)
        val output = file.startWrite()
        try {
            output.write(GSON.toJson(records).toByteArray())
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
}
