package io.legado.app.data.repository

import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSkipCreditsRepositoryTest {
    private val initial = AudioSkipCreditsDraft(true, 10, 20, 30, 40)

    @Test
    fun scopeCopiesLatestGlobalValuesOnlyWhenSwitchingToBookAndRetainsLocalValuesWhenSwitchingBack() {
        val book = initial.scope(false)
        assertEquals(30, book.bookOpen)
        assertEquals(40, book.bookClose)
        assertFalse(book.useGlobal)
        val changed = book.opening(7).closing(9).scope(true)
        assertEquals(7, changed.bookOpen)
        assertEquals(9, changed.bookClose)
        assertEquals(30, changed.opening)
        assertEquals(30, changed.scope(false).bookOpen)
    }

    @Test
    fun rawExistingSecondsArePreservedWhileDisplayAndUserEditsUseOriginalRange() {
        val draft =
            initial.copy(bookOpen = 500, bookClose = -1, globalOpen = 600, globalClose = 700)
        assertEquals(180, draft.opening)
        assertEquals(180, draft.closing)
        assertEquals(600, draft.scope(false).bookOpen)
        assertEquals(500, draft.bookOpen)
        assertEquals(180, draft.opening(999).globalOpen)
        assertEquals(0, draft.closing(-10).globalClose)
    }

    @Test
    fun scopeOrLocalChangeDoesNotWriteGlobalPreferencesAndOnlyRealCloseSavesBook() = runTest {
        val store = Fake()
        val repository =
            DefaultAudioSkipCreditsRepository(
                store,
                StandardTestDispatcher(testScheduler),
                AudioSkipCreditsWriteGate(),
            )
        repository.load()
        repository.write(initial.scope(false), 1, false, false)
        assertTrue(store.calls.isEmpty())
        repository.write(initial.scope(false).opening(8), 2, false, true)
        assertEquals(listOf("book"), store.calls)
        assertEquals(8, store.saved!!.bookOpen)
    }

    @Test
    fun nonCancellableWritesAreSerializedAndLateOlderDialogCannotOverwriteNewestDraft() = runTest {
        val store = Fake().apply { globalGate = CompletableDeferred() }
        val gate = AudioSkipCreditsWriteGate()
        val first =
            DefaultAudioSkipCreditsRepository(store, StandardTestDispatcher(testScheduler), gate)
        val second =
            DefaultAudioSkipCreditsRepository(store, StandardTestDispatcher(testScheduler), gate)
        val older = launch { first.write(initial.opening(50), 1, true, false) }
        runCurrent()
        older.cancel()
        val newer = launch { second.write(initial.opening(60).scope(false), 2, true, true) }
        runCurrent()
        assertEquals(listOf("globals"), store.calls)
        store.globalGate!!.complete(Unit)
        older.join()
        newer.join()
        first.write(initial, 1, true, true)
        assertEquals(60 to 40, store.globals)
        assertEquals(60, store.saved!!.bookOpen)
        assertEquals(listOf("globals", "globals", "book"), store.calls)
    }

    @Test
    fun failedSaveCanRetryWithoutAdvancingRevisionOrLosingGlobalChange() = runTest {
        val store = Fake().apply { saveFails = true }
        val repo =
            DefaultAudioSkipCreditsRepository(
                store,
                StandardTestDispatcher(testScheduler),
                AudioSkipCreditsWriteGate(),
            )
        assertTrue(runCatching { repo.write(initial.opening(70), 3, true, true) }.isFailure)
        assertEquals(70 to 40, store.globals)
        assertNull(store.saved)
        store.saveFails = false
        repo.write(initial.opening(70), 3, true, true)
        assertEquals(70, store.saved!!.globalOpen)
    }

    @Test
    fun loadingPreferencesAndBookPersistenceRunOnInjectedIoThread() = runBlocking {
        val main = Thread.currentThread()
        val store = Fake()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val repo =
                DefaultAudioSkipCreditsRepository(store, dispatcher, AudioSkipCreditsWriteGate())
            repo.load()
            repo.write(initial, 1, true, true)
            assertTrue(store.threads.isNotEmpty())
            assertTrue(store.threads.all { it !== main })
        } finally {
            dispatcher.close()
        }
    }

    private class Fake : AudioSkipCreditsStore {
        override val bookId = "book"
        val calls = mutableListOf<String>()
        val threads = mutableListOf<Thread>()
        var globals = 30 to 40
        var saved: AudioSkipCreditsDraft? = null
        var globalGate: CompletableDeferred<Unit>? = null
        var saveFails = false

        override suspend fun load(): AudioSkipCreditsDraft {
            threads += Thread.currentThread()
            return AudioSkipCreditsDraft(true, 10, 20, globals.first, globals.second)
        }

        override suspend fun globals(opening: Int, closing: Int) {
            threads += Thread.currentThread()
            calls += "globals"
            globalGate?.await()
            globals = opening to closing
        }

        override suspend fun saveBook(draft: AudioSkipCreditsDraft) {
            threads += Thread.currentThread()
            calls += "book"
            if (saveFails) error("disk failed")
            saved = draft
        }
    }
}
