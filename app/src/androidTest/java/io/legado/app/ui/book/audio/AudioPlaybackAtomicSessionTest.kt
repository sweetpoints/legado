package io.legado.app.ui.book.audio

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlaybackAtomicSessionTest {
    @Test
    fun largePrivatePayloadRestoresFromAtomicBackupAndLateWriterCannotReopenClosedSession() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.cacheDir, UUID.randomUUID().toString())
            try {
                val ticket = UUID.randomUUID().toString()
                val store = FileAudioPlaybackSessions(directory)
                val url = "https://book.example/" + "private-path/".repeat(40000)
                val checkpoint =
                    AudioPlaybackCheckpoint(
                        1,
                        url,
                        AudioCacheCheckpoint(
                            "cache-owner",
                            AudioCacheKind.Download,
                            url,
                            1,
                            12,
                            phase = AudioCachePhase.Claimed,
                        ),
                    )
                store.write(ticket, checkpoint)
                val body = File(directory, "$ticket.json")
                assertTrue(body.renameTo(File(directory, "$ticket.json.bak")))
                val restored = FileAudioPlaybackSessions(directory).read(ticket)
                assertEquals(checkpoint, restored)
                assertEquals(AudioRecovery.Cache, restored?.recoveryProjection()?.recovery)
                store.release(ticket)
                assertTrue(
                    runCatching { store.write(ticket, checkpoint.copy(revision = 2)) }.isFailure
                )
                assertEquals(null, store.read(ticket))
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun separateRepositoriesRejectEqualRevisionPayloadAndLateControllerClaim() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, UUID.randomUUID().toString())
        try {
            val ticket = UUID.randomUUID().toString()
            val first = FileAudioPlaybackSessions(directory)
            val second = FileAudioPlaybackSessions(directory)
            val initial = AudioPlaybackCheckpoint(bookUrl = "original-book")
            assertTrue(first.write(ticket, initial))
            assertFalse(second.write(ticket, initial.copy(bookUrl = "late-equal-version")))
            assertEquals(initial, second.read(ticket))

            val waiting = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val gated =
                object : AudioPlaybackSessions {
                    override suspend fun read(ticket: String) = first.read(ticket)

                    override suspend fun write(
                        ticket: String,
                        checkpoint: AudioPlaybackCheckpoint,
                    ): Boolean {
                        waiting.complete(Unit)
                        resume.await()
                        return first.write(ticket, checkpoint)
                    }

                    override suspend fun release(ticket: String) = first.release(ticket)
                }
            val oldController = AudioSessionController(ticket, gated, allowCreate = false)
            val oldClaim =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        oldController.update {
                            it.copy(closeToken = "old-close", closeClaimed = true)
                        }
                    }
                }
            waiting.await()
            val replacement = AudioSessionController(ticket, second, allowCreate = false)
            val newClaim = replacement.update {
                it.copy(bookUrl = "replacement-book", shelfToken = "new-shelf")
            }
            val newSnapshot = replacement.update { it.copy(shelfClaimed = true) }
            assertTrue(newSnapshot.revision > newClaim.revision)
            resume.complete(Unit)
            assertTrue(oldClaim.await().isFailure)
            assertEquals(newSnapshot, first.read(ticket))
            // A rejected controller must reload the accepted snapshot instead of caching its claim.
            assertEquals(newSnapshot, oldController.load())
            assertEquals(null, oldController.load().closeToken)
        } finally {
            directory.deleteRecursively()
        }
    }
}
