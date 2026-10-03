package io.legado.app.ui.book.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSessionControllerTest {
    private class MemorySessions : AudioPlaybackSessions {
        var value: AudioPlaybackCheckpoint? = null
        var closed = false
        var accepted: CompletableDeferred<Unit>? = null
        var continueWrite: CompletableDeferred<Unit>? = null

        override suspend fun read(ticket: String) = if (closed) null else value

        override suspend fun write(ticket: String, checkpoint: AudioPlaybackCheckpoint) {
            accepted?.complete(Unit)
            continueWrite?.await()
            check(!closed)
            value = checkpoint
        }

        override suspend fun release(ticket: String) {
            closed = true
            value = null
        }
    }

    @Test
    fun acceptedWriteCanceledBeforeReturningIsStillRecoverableWithoutReplay() = runBlocking {
        val disk = MemorySessions()
        val accepted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        disk.accepted = accepted
        disk.continueWrite = finish
        val owner = AudioSessionController("same-private-ticket", disk)
        val write =
            launch(start = CoroutineStart.UNDISPATCHED) {
                owner.update {
                    it.copy(
                        cache =
                            AudioCacheCheckpoint(
                                "clear-owner",
                                AudioCacheKind.Folder,
                                phase = AudioCachePhase.Accepted,
                            )
                    )
                }
            }
        accepted.await()
        write.cancel()
        finish.complete(Unit)
        write.join()
        val restored =
            AudioSessionController("same-private-ticket", disk, allowCreate = false).load()
        assertEquals(AudioCachePhase.Accepted, restored.cache?.phase)
        val state = restored.recoveryProjection()
        assertEquals(AudioRecovery.Cache, state.recovery)
        assertNull(state.cacheReady)
        assertNull(state.folderRequest)
        assertNull(state.bookNavigation)
    }

    @Test
    fun claimedNativeOperationStaysClaimedAndOnlyProjectsManualRecovery() = runBlocking {
        val disk = MemorySessions()
        val owner = AudioSessionController("ticket", disk)
        owner.update {
            it.copy(
                cache =
                    AudioCacheCheckpoint(
                        "claimed-owner",
                        AudioCacheKind.Folder,
                        phase = AudioCachePhase.Claimed,
                    )
            )
        }
        val restored = AudioSessionController("ticket", disk, allowCreate = false).load()
        assertEquals(AudioCachePhase.Claimed, restored.cache?.phase)
        assertEquals(AudioRecovery.Cache, restored.recoveryProjection().recovery)
        assertNull(restored.recoveryProjection().folderRequest)
    }

    @Test
    fun closedRestoredSessionCannotFallBackToAnUnrelatedCurrentBook() = runBlocking {
        val disk = MemorySessions()
        val owner = AudioSessionController("ticket", disk)
        owner.update { it.copy(bookUrl = "original-book") }
        owner.close()
        val failure = runCatching {
            AudioSessionController("ticket", disk, allowCreate = false).load()
        }
            .exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertNull(disk.value)
    }
}
