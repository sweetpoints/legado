package io.legado.app.ui.book.audio

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes a receipt with its payload before the UI is allowed to deliver native work. */
internal class AudioSessionController(
    private val ticket: String,
    private val sessions: AudioPlaybackSessions,
    private val allowCreate: Boolean = true,
) {
    private val writes = Mutex()
    private var checkpoint: AudioPlaybackCheckpoint? = null

    suspend fun load(): AudioPlaybackCheckpoint = writes.withLock {
        checkpoint ?: readOrCreate().also { checkpoint = it }
    }

    suspend fun update(
        change: (AudioPlaybackCheckpoint) -> AudioPlaybackCheckpoint
    ): AudioPlaybackCheckpoint =
        withContext(NonCancellable) {
            writes.withLock {
                val current = checkpoint ?: readOrCreate()
                val changed = change(current)
                if (changed == current) return@withLock current.also { checkpoint = it }
                val next = changed.copy(revision = current.revision + 1)
                // Another restored controller may already have accepted a newer receipt. A
                // rejected write must not become cached state or authorize a native effect.
                if (!sessions.write(ticket, next)) {
                    checkpoint = null
                    error("Audio session receipt was superseded")
                }
                checkpoint = next
                next
            }
        }

    private suspend fun readOrCreate(): AudioPlaybackCheckpoint =
        sessions.read(ticket)
            ?: if (allowCreate) AudioPlaybackCheckpoint()
            else error("Audio session is unavailable; reopen playback")

    suspend fun close() = writes.withLock { sessions.release(ticket) }
}
