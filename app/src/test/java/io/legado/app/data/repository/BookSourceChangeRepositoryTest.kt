package io.legado.app.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookSourceChangeRepositoryTest {
    private fun row(id: String) =
        ChapterSourceSearchRow(
            id,
            "source-$id",
            "Source",
            "Book",
            "Author",
            "Latest",
            null,
            -1,
            1,
            1,
            0,
            0,
            "full-$id",
        )

    private val target =
        ChapterSourceToc(
            "target",
            "full-book-metadata",
            "full-source-metadata",
            listOf(
                ChapterSourceChapter("chapter", 0, "Title", false, "Tag", "full-chapter-metadata")
            ),
            0,
        )

    @Test
    fun normalPreparationAllowsWebFileButAutomaticReplacementRequiresDirectoryAndNeverDeletesBeforeSuccess() =
        runTest {
            val store = Fake(target)
            val repo =
                DefaultBookSourceChangeRepository(store, StandardTestDispatcher(testScheduler))
            val normal = repo.prepare("session", row("new"))
            val automatic = repo.prepare("session", row("new"), row("old"))
            assertEquals(listOf(true, false), store.webFile)
            assertNull(normal.deleteAfter)
            assertEquals(row("old"), automatic.deleteAfter)
            assertEquals(target.bookJson, automatic.bookJson)
            assertEquals(target.sourceJson, automatic.sourceJson)
            assertEquals(target.chapters, automatic.chapters)
            assertTrue(store.deleted.isEmpty())
        }

    @Test
    fun consumedDurableReceiptCanBeAcknowledgedAndRepeatedConcurrentSuccessCallbacksDeleteCapturedOldSourceOnce() =
        runTest {
            val store = Fake(target)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val first = DefaultBookSourceChangeRepository(store, dispatcher)
            val second = DefaultBookSourceChangeRepository(store, dispatcher)
            val receipt = first.prepare("session", row("new"), row("old"))
            first.consume("session", receipt.key)
            coroutineScope {
                listOf(first, second, first)
                    .map { repo -> launch { repo.complete("session", receipt.key) } }
                    .joinAll()
            }
            val restored = second.receipt("session", receipt.key)
            assertTrue(restored.consumed)
            assertTrue(restored.acknowledged)
            assertEquals(listOf(row("old")), store.deleted)
            assertEquals(receipt.bookJson, restored.bookJson)
            assertEquals(receipt.chapters, restored.chapters)
        }

    @Test
    fun failedPreparationAndAbandonedHostChangeKeepOriginalSource() = runTest {
        val store = Fake(target).apply { failure = IllegalStateException("directory failed") }
        val repo = DefaultBookSourceChangeRepository(store, StandardTestDispatcher(testScheduler))
        assertFails { repo.prepare("session", row("new"), row("old")) }
        assertTrue(store.receipts.isEmpty())
        assertTrue(store.deleted.isEmpty())
        store.failure = null
        val prepared = repo.prepare("session", row("new"), row("old"))
        repo.consume("session", prepared.key)
        assertTrue(store.deleted.isEmpty())
        assertFalse(repo.receipt("session", prepared.key).acknowledged)
    }

    @Test
    fun cancelledNonCooperativePreparationDoesNotWriteOrPublishLateReceipt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store =
            Fake(target).apply { beforePrepare = { withContext(NonCancellable) { gate.await() } } }
        val repo = DefaultBookSourceChangeRepository(store, StandardTestDispatcher(testScheduler))
        var delivered = false
        val job = launch {
            repo.prepare("session", row("new"))
            delivered = true
        }
        runCurrent()
        job.cancel()
        gate.complete(Unit)
        job.join()
        assertFalse(delivered)
        assertTrue(store.receipts.isEmpty())
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun retryAfterFailedCompletionKeepsReceiptUnacknowledgedAndNormalSuccessDoesNotDeleteAnySource() =
        runTest {
            val store = Fake(target)
            val repo =
                DefaultBookSourceChangeRepository(store, StandardTestDispatcher(testScheduler))
            val receipt = repo.prepare("session", row("new"), row("old"))
            store.deleteFailure = IllegalStateException("IO failed")
            assertFails { repo.complete("session", receipt.key) }
            assertFalse(repo.receipt("session", receipt.key).acknowledged)
            store.deleteFailure = null
            repo.complete("session", receipt.key)
            val normal = repo.prepare("session", row("another"))
            repo.complete("session", normal.key)
            assertEquals(listOf(row("old")), store.deleted)
            assertTrue(repo.receipt("session", normal.key).acknowledged)
        }

    @Test
    fun sessionPersistenceCarriesFullMetadataAndCommandsUseImmutableCapturedRow() = runTest {
        val store = Fake(target)
        val repo = DefaultBookSourceChangeRepository(store, StandardTestDispatcher(testScheduler))
        val original = row("old")
        val snapshot =
            BookSourceChangeSession(
                ChapterSourceSearchRequest("Book", "Author", originalBookJson = "large original"),
                rows = listOf(original),
                mismatchId = "new",
                pendingReceipt = "receipt",
                revision = 12,
            )
        repo.write("session", snapshot)
        assertEquals(snapshot, repo.read("session"))
        repo.disable(original)
        repo.order(original, true)
        repo.score(original, -1)
        assertEquals(listOf("disable:old", "order:old:true", "score:old:-1"), store.commands)
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected failure")
        } catch (_: IllegalStateException) {}
    }

    private class Fake(private val target: ChapterSourceToc) : BookSourceChangeStore {
        val receipts = mutableMapOf<String, BookSourceChangeReceipt>()
        val sessions = mutableMapOf<String, BookSourceChangeSession>()
        val webFile = mutableListOf<Boolean>()
        val deleted = mutableListOf<ChapterSourceSearchRow>()
        val commands = mutableListOf<String>()
        var failure: Throwable? = null
        var deleteFailure: Throwable? = null
        var beforePrepare: suspend () -> Unit = {}

        override suspend fun prepare(
            row: ChapterSourceSearchRow,
            allowWebFile: Boolean,
        ): ChapterSourceToc {
            webFile += allowWebFile
            beforePrepare()
            failure?.let { throw it }
            return target
        }

        override suspend fun read(session: String) = sessions[session]

        override suspend fun write(session: String, snapshot: BookSourceChangeSession) {
            sessions[session] = snapshot
        }

        override suspend fun receipt(session: String, key: String) = receipts[key]

        override suspend fun writeReceipt(session: String, receipt: BookSourceChangeReceipt) {
            receipts[receipt.key] = receipt
        }

        override suspend fun delete(row: ChapterSourceSearchRow) {
            deleteFailure?.let { throw it }
            deleted += row
        }

        override suspend fun disable(row: ChapterSourceSearchRow) {
            commands += "disable:${row.id}"
        }

        override suspend fun order(row: ChapterSourceSearchRow, top: Boolean) {
            commands += "order:${row.id}:$top"
        }

        override suspend fun score(row: ChapterSourceSearchRow, score: Int) {
            commands += "score:${row.id}:$score"
        }
    }
}
