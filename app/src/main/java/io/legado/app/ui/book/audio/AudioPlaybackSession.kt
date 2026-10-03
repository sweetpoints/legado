package io.legado.app.ui.book.audio

import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Keep
internal enum class AudioCachePhase {
    FolderPending,
    FolderClaimed,
    Ready,
    Claimed,
    Accepted,
    Complete,
}

@Keep
internal enum class AudioCacheKind {
    Folder,
    Download,
    Clear,
}

@Keep
internal data class AudioCacheCheckpoint(
    val token: String,
    val kind: AudioCacheKind,
    val bookUrl: String? = null,
    val start: Int = 0,
    val endInclusive: Int = 0,
    val chapter: BookChapter? = null,
    val folder: String? = null,
    val phase: AudioCachePhase = AudioCachePhase.FolderPending,
) {
    fun action(): AudioCacheAction? =
        when (kind) {
            AudioCacheKind.Folder -> null
            AudioCacheKind.Download ->
                AudioCacheAction.Download(checkNotNull(bookUrl), start, endInclusive)
            AudioCacheKind.Clear ->
                AudioCacheAction.Clear(checkNotNull(bookUrl), checkNotNull(chapter))
        }
}

@Keep
internal data class AudioNavigationCheckpoint(
    val token: String,
    val book: Book,
    val claimed: Boolean = false,
    val complete: Boolean = false,
)

@Keep
internal data class AudioPlaybackCheckpoint(
    val revision: Long = 0,
    val bookUrl: String? = null,
    val cache: AudioCacheCheckpoint? = null,
    val navigation: AudioNavigationCheckpoint? = null,
    val closeToken: String? = null,
    val closeClaimed: Boolean = false,
    val shelfToken: String? = null,
    val shelfClaimed: Boolean = false,
)

/** Restored disk state never contains an immediately deliverable native effect. */
internal fun AudioPlaybackCheckpoint.recoveryProjection(): AudioPlayUiState =
    AudioPlayUiState(
        recovery =
            when {
                cache?.phase?.let { it != AudioCachePhase.Complete } == true -> AudioRecovery.Cache
                navigation?.complete == false -> AudioRecovery.Navigation
                closeToken != null -> AudioRecovery.Close
                shelfToken != null -> AudioRecovery.Shelf
                else -> null
            }
    )

internal interface AudioPlaybackSessions {
    suspend fun read(ticket: String): AudioPlaybackCheckpoint?

    suspend fun write(ticket: String, checkpoint: AudioPlaybackCheckpoint): Boolean

    suspend fun release(ticket: String)
}

/** The durable payload is private; only its UUID belongs to SavedState or a Fragment argument. */
internal class FileAudioPlaybackSessions(private val directory: File) : AudioPlaybackSessions {
    private fun body(ticket: String): AtomicFile {
        require(UUID.fromString(ticket).toString() == ticket)
        return AtomicFile(File(directory, "$ticket.json"))
    }

    private fun gate(ticket: String) =
        gates[(body(ticket).baseFile.canonicalPath.hashCode() and Int.MAX_VALUE) % gates.size]

    private fun closed(ticket: String): Boolean =
        listOf("$ticket.closed", "$ticket.closed.bak").any { File(directory, it).exists() }

    private fun readBody(ticket: String): AudioPlaybackCheckpoint? {
        if (closed(ticket)) return null
        val file = body(ticket)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use {
            GSON.fromJsonObject<AudioPlaybackCheckpoint>(it.readText()).getOrThrow()
        }
    }

    override suspend fun read(ticket: String) =
        withContext(Dispatchers.IO) { gate(ticket).withLock { readBody(ticket) } }

    override suspend fun write(ticket: String, checkpoint: AudioPlaybackCheckpoint) =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(ticket).withLock {
                check(!closed(ticket)) { "Audio session is closed" }
                if ((readBody(ticket)?.revision ?: -1) >= checkpoint.revision) return@withLock false
                check(directory.isDirectory || directory.mkdirs())
                val file = body(ticket)
                val output = file.startWrite()
                try {
                    output.write(GSON.toJson(checkpoint).toByteArray())
                    file.finishWrite(output)
                    true
                } catch (error: Throwable) {
                    file.failWrite(output)
                    throw error
                }
            }
        }

    override suspend fun release(ticket: String) =
        withContext(Dispatchers.IO + NonCancellable) {
            gate(ticket).withLock {
                check(directory.isDirectory || directory.mkdirs())
                val marker = AtomicFile(File(directory, "$ticket.closed"))
                val output = marker.startWrite()
                try {
                    output.write(1)
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
                body(ticket).delete()
            }
        }

    private companion object {
        val gates = Array(64) { Mutex() }
    }
}
