package io.legado.app.data.repository

import io.legado.app.model.book.ChapterSourceCacheDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterSourceContentRepositoryTest {
    private val first = ChapterSourceChapter("one", 1, "One", false, null, "one-json")
    private val second = ChapterSourceChapter("two", 2, "Two", false, null, "two-json")
    private val volume = ChapterSourceChapter("volume", 3, "Volume", true, null, "volume-json")
    private val toc =
        ChapterSourceToc("target", "book-json", "source-json", listOf(first, second, volume), 1)

    @Test
    fun cachingUsesSortedDistinctPositionsAndDurableJournalBeforeBodyCommit() = runTest {
        val store =
            Fake().apply {
                bodies[0] = "First。"
                bodies[1] = "Second"
            }
        val repo =
            DefaultChapterSourceContentRepository(store, StandardTestDispatcher(testScheduler))
        val receipt =
            repo.cache("session", toc, listOf(1, 0, 1), "original", first) {
                store.calls += "commit-boundary"
            }
        assertEquals(listOf(0, 1), store.readPositions)
        assertEquals("First。\nSecond", receipt.body)
        assertEquals(2, receipt.targetPosition)
        assertEquals(
            listOf("commit-boundary", "journal:false", "save", "journal:true"),
            store.calls,
        )
        assertTrue(receipt.committed)
        assertEquals(receipt, repo.receipt("session", receipt.key))
    }

    @Test
    fun emptyBodyOrVolumeSelectionNeverCreatesJournalOrTouchesCache() = runTest {
        val store = Fake().apply { bodies[0] = " \n " }
        val repo =
            DefaultChapterSourceContentRepository(store, StandardTestDispatcher(testScheduler))
        assertTrue(
            runCatching { repo.cache("session", toc, listOf(0), "original", first) }.isFailure
        )
        assertTrue(
            runCatching { repo.cache("session", toc, listOf(2), "original", first) }.isFailure
        )
        assertTrue(store.calls.isEmpty())
        assertTrue(store.receipts.isEmpty())
    }

    @Test
    fun cancellationBeforeCommitDoesNotWriteJournalAndCancellationDuringCommitFinishesReceipt() =
        runTest {
            val store =
                Fake().apply {
                    contentGate = CompletableDeferred()
                    bodies[0] = "Body"
                }
            val repo =
                DefaultChapterSourceContentRepository(store, StandardTestDispatcher(testScheduler))
            val canceled = launch { repo.cache("session", toc, listOf(0), "original", first) }
            runCurrent()
            canceled.cancel()
            store.contentGate!!.complete(Unit)
            canceled.join()
            assertTrue(store.receipts.isEmpty())
            assertTrue(store.calls.isEmpty())
            store.contentGate = null
            store.saveGate = CompletableDeferred()
            val committing = launch { repo.cache("session", toc, listOf(0), "original", first) }
            runCurrent()
            assertEquals(listOf("journal:false", "save"), store.calls)
            committing.cancel()
            store.saveGate!!.complete(Unit)
            committing.join()
            assertTrue(store.receipts.values.single().committed)
            assertEquals("Body", store.cachedBody)
        }

    @Test
    fun crashAfterBodyWriteRecoversWithoutSavingBodyTwiceAndConsumedReceiptCannotRecoverAgain() =
        runTest {
            val store =
                Fake().apply {
                    cachedBody = "Already written"
                    receipts["receipt"] =
                        ChapterSourceReceipt(
                            "receipt",
                            ChapterSourceReceiptKind.Cache,
                            body = "Already written",
                            chapterIndex = 1,
                            targetPosition = 2,
                        )
                }
            val repo =
                DefaultChapterSourceContentRepository(store, StandardTestDispatcher(testScheduler))
            val recovered = repo.recoverCache("session", "original", listOf(first, second))!!
            assertTrue(recovered.committed)
            assertFalse(store.calls.contains("save"))
            repo.consume("session", recovered.key)
            assertNull(repo.recoverCache("session", "original", listOf(first, second)))
            assertTrue(repo.receipt("session", recovered.key).consumed)
        }

    @Test
    fun crashBeforeBodyWriteCompletesOriginalJournalAndPreservesSameReceiptIdentity() = runTest {
        val store =
            Fake().apply {
                receipts["receipt"] =
                    ChapterSourceReceipt(
                        "receipt",
                        ChapterSourceReceiptKind.Cache,
                        body = "Pending",
                        chapterIndex = 1,
                        previousBodyHash = ChapterSourceCacheDigest.of(null),
                    )
            }
        val repo =
            DefaultChapterSourceContentRepository(store, StandardTestDispatcher(testScheduler))
        val recovered = repo.recoverCache("session", "original", listOf(first))!!
        assertEquals("receipt", recovered.key)
        assertEquals("Pending", store.cachedBody)
        assertEquals(listOf("save", "journal:true"), store.calls)
        repo.recoverCache("session", "original", listOf(first))
        assertEquals(1, store.calls.count { it == "save" })
    }

    @Test
    fun recoveryRejectsConcurrentReplacementAndMissingHashWithoutWritingNewerBody() = runTest {
        val store =
            Fake().apply {
                cachedBody = "Newer body"
                receipts["receipt"] =
                    ChapterSourceReceipt(
                        "receipt",
                        ChapterSourceReceiptKind.Cache,
                        body = "Pending",
                        chapterIndex = 1,
                        previousBodyHash = ChapterSourceCacheDigest.of("Original"),
                    )
            }
        val repo =
            DefaultChapterSourceContentRepository(store, StandardTestDispatcher(testScheduler))
        assertTrue(
            runCatching { repo.recoverCache("session", "original", listOf(first)) }.isFailure
        )
        assertEquals("Newer body", store.cachedBody)
        assertFalse(store.receipts.getValue("receipt").committed)
        store.receipts["receipt"] = store.receipts.getValue("receipt").copy(previousBodyHash = null)
        store.calls.clear()
        assertTrue(
            runCatching { repo.recoverCache("session", "original", listOf(first)) }.isFailure
        )
        assertTrue(store.calls.isEmpty())
        assertEquals("Newer body", store.cachedBody)
        assertNotEquals(ChapterSourceCacheDigest.of(null), ChapterSourceCacheDigest.of(""))
    }

    @Test
    fun abandonOnlyUncommittedJournalAllowsFreshCacheWithoutDiscardingCommittedResultsOrNewerBody() =
        runTest {
            val store =
                Fake().apply {
                    cachedBody = "Newer body"
                    receipts["conflict"] =
                        ChapterSourceReceipt(
                            "conflict",
                            ChapterSourceReceiptKind.Cache,
                            body = "Stale",
                            chapterIndex = 1,
                            previousBodyHash = ChapterSourceCacheDigest.of("Old body"),
                        )
                    bodies[0] = "Fresh body"
                }
            val repo =
                DefaultChapterSourceContentRepository(store, StandardTestDispatcher(testScheduler))
            assertTrue(
                runCatching { repo.recoverCache("session", "original", listOf(first)) }.isFailure
            )
            store.calls.clear()
            repo.abandonUncommittedCache("session")
            assertTrue(store.receipts.getValue("conflict").consumed)
            assertEquals("Newer body", store.cachedBody)
            assertFalse(store.calls.contains("save"))
            assertNull(repo.recoverCache("session", "original", listOf(first)))
            val fresh = repo.cache("session", toc, listOf(0), "original", first)
            assertEquals("Fresh body", store.cachedBody)
            assertTrue(fresh.committed)
            repo.abandonUncommittedCache("session")
            assertFalse(store.receipts.getValue(fresh.key).consumed)
            assertEquals(fresh, repo.recoverCache("session", "original", listOf(first)))
        }

    @Test
    fun contentAndChangePayloadsArePersistedBySmallReceiptWhileKeepingEntireTocMetadata() =
        runTest {
            val store = Fake().apply { bodies[0] = "x".repeat(1200000) }
            val repo =
                DefaultChapterSourceContentRepository(store, StandardTestDispatcher(testScheduler))
            val content = repo.content("session", toc, 0)
            assertEquals(1200000, repo.receipt("session", content.key).body!!.length)
            assertTrue(content.key.length < 100)
            val changed = repo.change("session", toc, "old-source")
            assertEquals(toc.bookJson, changed.bookJson)
            assertEquals(toc.sourceJson, changed.sourceJson)
            assertEquals(toc.chapters, changed.chapters)
            assertEquals("old-source", changed.deleteAfterId)
            assertTrue(store.calls.none { it == "delete" })
        }

    @Test
    fun bodyMergeRetainsExistingWhitespaceAndInsertsOnlyMissingSentenceBoundaryNewline() {
        assertEquals("First。\nSecond", mergeChapterSourceBody(listOf("First。", "Second")))
        assertEquals("First。\nSecond", mergeChapterSourceBody(listOf("First。\n", "Second")))
        assertEquals("First Second", mergeChapterSourceBody(listOf("First ", "Second")))
        assertEquals("", mergeChapterSourceBody(emptyList()))
    }

    private class Fake : ChapterSourceContentStore {
        val bodies = mutableMapOf<Int, String>()
        val readPositions = mutableListOf<Int>()
        val receipts = linkedMapOf<String, ChapterSourceReceipt>()
        val calls = mutableListOf<String>()
        var cachedBody: String? = null
        var contentGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null

        override suspend fun original(bookJson: String) = emptyList<ChapterSourceChapter>()

        override suspend fun toc(
            row: ChapterSourceSearchRow,
            index: Int,
            title: String,
        ): ChapterSourceToc = error("not used")

        override suspend fun content(toc: ChapterSourceToc, position: Int): String {
            contentGate?.await()
            readPositions += position
            return bodies[position].orEmpty()
        }

        override suspend fun saveText(
            bookJson: String,
            chapter: ChapterSourceChapter,
            body: String,
            expectedPreviousHash: String,
        ): Boolean {
            calls += "save"
            saveGate?.await()
            if (ChapterSourceCacheDigest.of(cachedBody) != expectedPreviousHash) return false
            cachedBody = body
            return true
        }

        override suspend fun cachedText(bookJson: String, chapter: ChapterSourceChapter) =
            cachedBody

        override suspend fun read(session: String): ChapterSourceSession? = null

        override suspend fun write(session: String, snapshot: ChapterSourceSession) = Unit

        override suspend fun receipt(session: String, key: String) = receipts[key]

        override suspend fun writeReceipt(session: String, receipt: ChapterSourceReceipt) {
            calls += "journal:${receipt.committed}"
            receipts[receipt.key] = receipt
        }

        override suspend fun receipts(session: String) = receipts.values.toList()

        override suspend fun deleteSource(row: ChapterSourceSearchRow) {
            calls += "delete"
        }

        override suspend fun disableSource(row: ChapterSourceSearchRow) = Unit

        override suspend fun order(row: ChapterSourceSearchRow, top: Boolean) = Unit

        override suspend fun score(row: ChapterSourceSearchRow, score: Int) = Unit

        override suspend fun groups() = emptyList<String>()
    }
}
