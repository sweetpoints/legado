package io.legado.app.ui.book.audio.config

import io.legado.app.data.entities.Book
import io.legado.app.data.repository.AppAudioSkipCreditsStore
import io.legado.app.data.repository.AudioSkipCreditsDraft
import io.legado.app.data.repository.AudioSkipCreditsRepository
import io.legado.app.data.repository.DefaultAudioSkipCreditsRepository
import io.legado.app.ui.book.audio.AudioNavigationCheckpoint
import io.legado.app.ui.book.audio.AudioPlaybackSessions
import io.legado.app.ui.book.audio.AudioSessionController
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resolves the private launch snapshot before selecting the database identity or write gate. */
internal class AudioSkipCreditsSessionRepository(
    ticket: String,
    sessions: AudioPlaybackSessions,
    private val seed: Book? = null,
    private val factory: (Book) -> AudioSkipCreditsRepository = {
        DefaultAudioSkipCreditsRepository(AppAudioSkipCreditsStore(it.bookUrl, it))
    },
) : AudioSkipCreditsRepository {
    private val session = AudioSessionController(ticket, sessions, allowCreate = seed != null)
    private val loading = Mutex()
    private var delegate: AudioSkipCreditsRepository? = null
    var bookId: String? = null
        private set

    private suspend fun repository(): AudioSkipCreditsRepository = loading.withLock {
        delegate
            ?: run {
                val current = session.load()
                val book =
                    current.navigation?.book
                        ?: seed?.copy(readConfig = seed.readConfig?.copy())
                        ?: error("Audio configuration session is unavailable; reopen playback")
                if (current.navigation == null) {
                    session.update {
                        it.copy(navigation = AudioNavigationCheckpoint("config", book))
                    }
                }
                bookId = book.bookUrl
                factory(book).also { delegate = it }
            }
    }

    override suspend fun close() = session.close()

    override suspend fun load() = repository().load()

    override suspend fun write(
        draft: AudioSkipCreditsDraft,
        revision: Long,
        globalsChanged: Boolean,
        saveBook: Boolean,
    ) {
        repository().write(draft, revision, globalsChanged, saveBook)
    }
}
