package io.legado.app.ui.book.audio

import io.legado.app.data.entities.Book
import io.legado.app.data.repository.AudioSkipCreditsDraft
import io.legado.app.data.repository.AudioSkipCreditsRepository
import io.legado.app.ui.book.audio.config.AudioSkipCreditsSessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSkipCreditsPrivateSessionTest {
    private class MemorySessions : AudioPlaybackSessions {
        var payload: AudioPlaybackCheckpoint? = null

        override suspend fun read(ticket: String) = payload

        override suspend fun write(ticket: String, checkpoint: AudioPlaybackCheckpoint): Boolean {
            payload = checkpoint
            return true
        }

        override suspend fun release(ticket: String) {
            payload = null
        }
    }

    @Test
    fun recreatedConfigSelectsOriginalPrivateBookAndReleasesAfterAcceptedWrite() = runBlocking {
        val sessions = MemorySessions()
        val book = Book(bookUrl = "https://audio/" + "large/".repeat(50000), name = "Original")
        val draft = AudioSkipCreditsDraft(false, 12, 34, 5, 6)
        var selected: String? = null
        var accepted = false
        val factory: (Book) -> AudioSkipCreditsRepository = { snapshot ->
            selected = snapshot.bookUrl
            object : AudioSkipCreditsRepository {
                override suspend fun load() = draft

                override suspend fun write(
                    draft: AudioSkipCreditsDraft,
                    revision: Long,
                    globalsChanged: Boolean,
                    saveBook: Boolean,
                ) {
                    accepted = saveBook
                }
            }
        }
        AudioSkipCreditsSessionRepository("private-key", sessions, book, factory).load()
        val restored = AudioSkipCreditsSessionRepository("private-key", sessions, factory = factory)
        assertEquals(draft, restored.load())
        assertEquals(book.bookUrl, selected)
        restored.write(draft, 1, false, true)
        assertTrue(accepted)
        restored.close()
        assertTrue(
            runCatching {
                AudioSkipCreditsSessionRepository("private-key", sessions, factory = factory).load()
            }
                .isFailure
        )
    }
}
