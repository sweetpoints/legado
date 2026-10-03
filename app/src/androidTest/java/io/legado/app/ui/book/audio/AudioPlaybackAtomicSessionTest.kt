package io.legado.app.ui.book.audio

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
