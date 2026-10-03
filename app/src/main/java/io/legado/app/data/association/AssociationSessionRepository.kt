package io.legado.app.data.association

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Keep
enum class AssociationHostKind {
    File,
    Online,
}

@Keep
enum class AssociationInputKind {
    View,
    SharedUri,
    SharedUris,
    SharedText,
    Invalid,
}

/** Complete launch content stays private; a recreated host saves only its session UUID. */
@Keep
data class AssociationInput(
    val host: AssociationHostKind,
    val kind: AssociationInputKind,
    val uris: List<String> = emptyList(),
    val text: String? = null,
    val mimeType: String? = null,
    val intentFlags: Int = 0,
)

@Keep
enum class AssociationPhase {
    Ready,
    Loading,
    Preview,
    Directory,
    Unsupported,
    ReadConfig,
    Finished,
    Failed,
}

@Keep
enum class AssociationNativeKind {
    StoragePermission,
    SelectDirectory,
    ImportDialog,
    OnlineImport,
    OpenBook,
    Finish,
}

@Keep
data class AssociationNativeReceipt(
    val token: String,
    val generation: Long,
    val kind: AssociationNativeKind,
    val type: String? = null,
    val payload: String? = null,
)

@Keep
data class AssociationBookPreview(
    val id: String,
    val fileUri: String,
    val fileName: String,
    val bookJson: String,
    val onBookshelf: Boolean = false,
    val isDirectory: Boolean = false,
)

@Keep
data class AssociationOperation(
    val token: String,
    val generation: Long,
    val kind: String,
    val payload: String? = null,
    val accepted: Boolean = false,
)

@Keep
data class AssociationSession(
    val input: AssociationInput,
    val revision: Long = 0,
    val generation: Long = 0,
    val phase: AssociationPhase = AssociationPhase.Ready,
    val previews: List<AssociationBookPreview> = emptyList(),
    val selectedIds: List<String> = emptyList(),
    val openSingleBook: Boolean = false,
    val storagePermissionGranted: Boolean = false,
    val importAfterDirectory: Boolean = true,
    val choosingDirectory: Boolean = false,
    val importType: String? = null,
    val importSource: String? = null,
    val unsupportedName: String? = null,
    val unsupportedUri: String? = null,
    val readConfigFile: String? = null,
    val operation: AssociationOperation? = null,
    val effects: List<AssociationNativeReceipt> = emptyList(),
    val claimedEffects: List<AssociationNativeReceipt> = emptyList(),
    val error: String? = null,
) {
    /** A stale native result cannot acknowledge a replacement request, even with the same kind. */
    fun claim(token: String, ownerGeneration: Long): AssociationSession? {
        val receipt =
            effects.firstOrNull { it.token == token && it.generation == ownerGeneration }
                ?: return null
        if (generation != ownerGeneration) return null
        return copy(
            revision = revision + 1,
            effects = effects - receipt,
            claimedEffects = claimedEffects + receipt,
        )
    }

    fun acknowledge(token: String, ownerGeneration: Long): AssociationSession? {
        val receipt =
            claimedEffects.firstOrNull {
                it.token == token && it.generation == ownerGeneration
            } ?: return null
        if (generation != ownerGeneration) return null
        return copy(revision = revision + 1, claimedEffects = claimedEffects - receipt)
    }
}

interface AssociationSessionRepository {
    suspend fun create(input: AssociationInput): String

    suspend fun read(ticket: String): AssociationSession

    suspend fun write(ticket: String, value: AssociationSession): Boolean

    suspend fun writeBytes(ticket: String, name: String, bytes: ByteArray)

    suspend fun readBytes(ticket: String, name: String): ByteArray

    suspend fun release(ticket: String)
}

class AssociationSessionClosed : IllegalStateException("Import session is closed")

/** Shared bounded gates serialize restored owners, byte payloads and the permanent close fence. */
class FileAssociationSessionRepository(
    context: Context,
    private val directory: File =
        File(context.applicationContext.filesDir, "association-import-sessions"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeWrite: (AssociationSession) -> Unit = {},
) : AssociationSessionRepository {
    private fun folder(ticket: String): File {
        require(runCatching { UUID.fromString(ticket) }.isSuccess)
        return File(directory, ticket)
    }

    private fun body(ticket: String) = AtomicFile(File(folder(ticket), "session.json"))

    private fun fence(ticket: String) = AtomicFile(File(directory, "$ticket.closed"))

    private fun closed(ticket: String) =
        fence(ticket).baseFile.let {
            it.exists() || File(it.path + ".bak").exists()
        }

    private fun gate(ticket: String) =
        gates[(folder(ticket).canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun checkOpen(ticket: String) {
        if (closed(ticket)) throw AssociationSessionClosed()
    }

    private fun readBody(ticket: String): AssociationSession {
        checkOpen(ticket)
        return body(ticket).openRead().bufferedReader().use {
            val value = GSON.fromJsonObject<AssociationSession>(it.readText()).getOrThrow()
            // Gson can allocate a Kotlin DTO without running its constructor. Reject missing
            // required fields rather than letting a corrupt restore become a fresh empty import.
            val input = requireNotNull(value.input)
            requireNotNull(input.host)
            requireNotNull(input.kind)
            requireNotNull(input.uris)
            requireNotNull(value.phase)
            requireNotNull(value.previews)
            requireNotNull(value.selectedIds)
            requireNotNull(value.effects)
            requireNotNull(value.claimedEffects)
            require(value.revision >= 0 && value.generation >= 0)
            value
        }
    }

    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    override suspend fun create(input: AssociationInput): String {
        val ticket = UUID.randomUUID().toString()
        try {
            withContext(dispatcher + NonCancellable) {
                gate(ticket).withLock {
                    val value = AssociationSession(input)
                    beforeWrite(value)
                    writeAtomic(body(ticket), GSON.toJson(value).toByteArray())
                }
            }
            currentCoroutineContext().ensureActive()
            return ticket
        } catch (failure: Throwable) {
            // Cancellation on the IO return can follow an accepted creation. This ticket never
            // reached its host, so release only this private allocation before rethrowing.
            withContext(NonCancellable) { release(ticket) }
            throw failure
        }
    }

    override suspend fun read(ticket: String): AssociationSession =
        withContext(dispatcher) {
            gate(ticket).withLock { readBody(ticket) }
        }

    override suspend fun write(ticket: String, value: AssociationSession): Boolean =
        withContext(dispatcher + NonCancellable) {
            gate(ticket).withLock {
                checkOpen(ticket)
                // A missing/restoration-failed session must never be silently recreated by a late
                // VM.
                val current = readBody(ticket)
                if (value.revision <= current.revision) return@withLock false
                beforeWrite(value)
                writeAtomic(body(ticket), GSON.toJson(value).toByteArray())
                true
            }
        }

    private fun bytesFile(ticket: String, name: String): AtomicFile {
        require(
            name.isNotBlank() &&
                name == File(name).name &&
                name !in setOf("session.json", "session.json.bak", "session.json.new", ".", "..")
        )
        return AtomicFile(File(folder(ticket), name))
    }

    override suspend fun writeBytes(ticket: String, name: String, bytes: ByteArray) {
        withContext(dispatcher + NonCancellable) {
            gate(ticket).withLock {
                readBody(ticket)
                val file = bytesFile(ticket, name)
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                    check(file.openRead().use { it.readBytes() }.contentEquals(bytes)) {
                        "An accepted payload is immutable; allocate a new payload name"
                    }
                } else {
                    writeAtomic(file, bytes)
                }
            }
        }
    }

    override suspend fun readBytes(ticket: String, name: String): ByteArray =
        withContext(dispatcher) {
            gate(ticket).withLock {
                readBody(ticket)
                bytesFile(ticket, name).openRead().use { it.readBytes() }
            }
        }

    /**
     * Local staging shares the close fence gate. An accepted release cannot race a still-running
     * extractor and allow it to recreate this UUID directory. The operation stays cancellable;
     * network requests and checkpoint writes must run outside this non-reentrant lease.
     */
    suspend fun <T> withOwnedDirectory(ticket: String, operation: suspend (File) -> T): T =
        withOwnedSession(ticket) { directory, _ -> operation(directory) }

    suspend fun <T> withOwnedSession(
        ticket: String,
        operation: suspend (File, AssociationSession) -> T,
    ): T =
        withContext(dispatcher) {
            gate(ticket).withLock {
                val session = readBody(ticket)
                operation(folder(ticket), session)
            }
        }

    override suspend fun release(ticket: String) {
        withContext(dispatcher + NonCancellable) {
            gate(ticket).withLock {
                if (!closed(ticket)) writeAtomic(fence(ticket), byteArrayOf(1))
                val ownedFolder = folder(ticket)
                clearAssociationStagingResources(ownedFolder)
                if (ownedFolder.exists())
                    check(ownedFolder.deleteRecursively()) {
                        "Import session cleanup failed"
                    }
                check(!ownedFolder.exists()) { "Import session cleanup failed" }
            }
        }
    }

    companion object {
        private val gates = Array(64) { Mutex() }
    }
}
