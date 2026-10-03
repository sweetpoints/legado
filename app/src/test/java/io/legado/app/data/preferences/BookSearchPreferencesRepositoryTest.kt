package io.legado.app.data.preferences

import io.legado.app.model.webBook.BookSearchPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSearchPreferencesRepositoryTest {
    private open class Store : BookSearchPreferencesStore {
        var snapshot = BookSearchPreferences()
        val callers = mutableListOf<Thread>()
        private val updates = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        var failWrites = false

        override fun changes(): Flow<Unit> = updates

        override suspend fun load(): BookSearchPreferences {
            recordCaller()
            return snapshot
        }

        override suspend fun precision(value: Boolean) {
            accept(snapshot.copy(precision = value))
        }

        override suspend fun showReadRecord(value: Boolean) {
            accept(snapshot.copy(showReadRecord = value))
        }

        override suspend fun resultFilter(value: String) {
            accept(snapshot.copy(resultFilter = value))
        }

        override suspend fun scope(value: String) {
            accept(snapshot.copy(scope = value))
        }

        private fun accept(value: BookSearchPreferences) {
            recordCaller()
            check(!failWrites) { "synthetic write failure" }
            snapshot = value
            updates.tryEmit(Unit)
        }

        private fun recordCaller() {
            synchronized(callers) { callers += Thread.currentThread() }
        }
    }

    @Test
    fun defaultAndObservedSnapshotsRunOnIoAndRetainGlobalKeys() = runBlocking {
        val caller = Thread.currentThread()
        val store = Store()
        val repository = DefaultBookSearchPreferencesRepository(store)
        assertEquals(BookSearchPreferences(), repository.observe().first())
        repository.precision(true)
        repository.showReadRecord(false)
        repository.resultFilter(" raw\nfilter ")
        repository.scope("Named::https://example.invalid/source")
        assertEquals(
            BookSearchPreferences(
                precision = true,
                showReadRecord = false,
                resultFilter = " raw\nfilter ",
                scope = "Named::https://example.invalid/source",
            ),
            repository.observe().first(),
        )
        assertTrue(store.callers.isNotEmpty())
        assertTrue(store.callers.all { it !== caller })
    }

    @Test
    fun eachAcceptedWritePreservesOtherFieldsAndFailedWriteLeavesPreviousSnapshot() = runBlocking {
        val store =
            Store().apply {
                snapshot =
                    BookSearchPreferences(
                        precision = true,
                        showReadRecord = false,
                        resultFilter = "existing",
                        scope = "One,Two",
                        loadCoverOnlyWifi = true,
                    )
            }
        val repository = DefaultBookSearchPreferencesRepository(store)
        val written = repository.resultFilter("replacement")
        assertTrue(written.precision)
        assertFalse(written.showReadRecord)
        assertEquals("One,Two", written.scope)
        assertTrue(written.loadCoverOnlyWifi)
        store.failWrites = true
        assertTrue(runCatching { repository.scope("rejected") }.isFailure)
        assertEquals(written, repository.load())
    }

    @Test
    fun callerCancellationAfterPreferenceCommitStillCompletesAcceptedSnapshot() = runBlocking {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : Store() {
                override suspend fun precision(value: Boolean) {
                    super.precision(value)
                    committed.complete(Unit)
                    release.await()
                }
            }
        val repository = DefaultBookSearchPreferencesRepository(store)
        val writing = launch { repository.precision(true) }
        committed.await()
        writing.cancel()
        release.complete(Unit)
        writing.join()
        assertTrue(repository.load().precision)
        assertTrue(repository.observe().first().precision)
    }
}
