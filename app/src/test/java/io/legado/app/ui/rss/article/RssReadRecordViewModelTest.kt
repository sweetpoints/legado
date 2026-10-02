package io.legado.app.ui.rss.article

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssReadRecordViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<RssReadRecordViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Fake : RssReadRecordRepository {
        var items = listOf(RssReadRecordItem("small-key", "Title", "https://article", "source"))
        var latest: RssReadRecord? = RssReadRecord("https://article", "Latest", origin = "source", durPos = 80, type = 2)
        var loads = 0; var counts = 0; var deletes = 0; var count = 7; var fail = false
        var origins = mutableListOf<String?>()
        var loadGate: CompletableDeferred<Unit>? = null; var countGate: CompletableDeferred<Unit>? = null
        var clearGate: CompletableDeferred<Unit>? = null; var resolveGate: CompletableDeferred<Unit>? = null
        var ignoreCancellation = false
        override suspend fun load(origin: String?): List<RssReadRecordItem> { loads++; origins += origin; if (ignoreCancellation) withContext(NonCancellable) { loadGate?.await() } else loadGate?.await(); if (fail) error("failed"); return items }
        override suspend fun count(origin: String?): Int { counts++; origins += origin; if (ignoreCancellation) withContext(NonCancellable) { countGate?.await() } else countGate?.await(); return count }
        override suspend fun clear(origin: String?) { deletes++; origins += origin; clearGate?.await(); if (fail) error("failed"); items = emptyList() }
        override suspend fun resolve(key: String, origin: String?): RssReadRecord? { origins += origin; resolveGate?.await(); return latest?.copy() }
    }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle(), origin: String? = null) = RssReadRecordViewModel(repo, saved, origin).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.forEach { it.stop() }; runCurrent() } }
    @Test fun loadFailureIsRetryableAndEmptyOriginRemainsExactOnRestoration() = test {
        val repo = Fake().apply { fail = true }; val saved = SavedStateHandle(); val first = model(repo, saved, ""); runCurrent()
        assertEquals("failed", first.state.value.error); first.requestClear(); first.read("small-key"); assertEquals(0, repo.counts)
        repo.fail = false; first.load(); runCurrent(); val restored = model(repo, copy(saved), "different"); runCurrent()
        assertEquals(listOf("", "", ""), repo.origins); assertEquals(1, restored.state.value.items.size)
    }
    @Test fun clearConfirmationCountsCurrentRowsAndCancellationNeverDeletes() = test {
        val repo = Fake(); val model = model(repo, origin = "source"); runCurrent(); model.requestClear(); runCurrent()
        assertEquals(7, model.state.value.clearCount); model.cancelClear(); assertNull(model.state.value.clearCount); assertEquals(0, repo.deletes)
        model.requestClear(); runCurrent(); model.confirmClear(); model.confirmClear(); runCurrent()
        assertEquals(1, repo.deletes); assertTrue(model.state.value.items.isEmpty()); assertNull(model.state.value.clearCount); assertTrue(repo.origins.all { it == "source" })
    }
    @Test fun confirmationRestorationRecountsInsteadOfSavingStaleCountAndPreservesNullOrigin() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent(); first.requestClear(); runCurrent()
        repo.count = 2; val restored = model(repo, copy(saved), "wrong"); runCurrent()
        assertEquals(2, restored.state.value.clearCount); assertTrue(repo.origins.all { it == null })
    }
    @Test fun cancelledLateCountDoesNotResurrectDialogAndClearFailureCanRetry() = test {
        val repo = Fake().apply { countGate = CompletableDeferred(); ignoreCancellation = true }; val model = model(repo); runCurrent(); model.requestClear(); runCurrent(); model.cancelClear()
        repo.countGate!!.complete(Unit); runCurrent(); assertNull(model.state.value.clearCount); assertFalse(model.state.value.busy)
        model.requestClear(); runCurrent(); repo.fail = true; model.confirmClear(); runCurrent(); assertEquals("failed", model.state.value.error)
        repo.fail = false; model.confirmClear(); runCurrent(); assertEquals(2, repo.deletes); assertTrue(model.state.value.items.isEmpty())
    }
    @Test fun readRestoresSmallKeyAndReReadsLatestEntityThenClosesOnlyOnDelivery() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved, "source"); runCurrent(); first.read("small-key")
        val effect = first.state.value.effect!!; first.read("small-key"); assertEquals(effect, first.state.value.effect)
        val restoredSaved = copy(saved); val restored = model(repo, restoredSaved); runCurrent(); assertEquals(effect, restored.state.value.effect)
        val record = restored.resolve(effect.id)!!; assertEquals(80, record.durPos); assertEquals(2, record.type); assertEquals("Latest", record.title)
        restored.delivered(effect.id); assertTrue(restored.state.value.finished)
        val again = model(repo, copy(restoredSaved)); runCurrent(); assertTrue(again.state.value.finished); assertEquals(2, repo.loads)
        assertFalse(saved.keys().mapNotNull { saved.get<Any?>(it) }.filterIsInstance<String>().any { it == "https://article" })
    }
    @Test fun missingOrDeletedRecordCannotDeliverAndBrowserDoesNotCloseTheList() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); model.browser("small-key"); val browser = model.state.value.effect!!
        assertNotNull(model.resolve(browser.id)); model.delivered(browser.id); assertFalse(model.state.value.finished)
        repo.latest = null; repo.items = emptyList(); model.read("small-key"); val read = model.state.value.effect!!
        assertNull(model.resolve(read.id)); runCurrent(); assertNull(model.state.value.effect); assertTrue(model.state.value.items.isEmpty())
    }
    @Test fun cancelRejectsUncooperativeLateLoadAndResolutionReturningAfterClose() = test {
        val repo = Fake().apply { loadGate = CompletableDeferred(); ignoreCancellation = true }; val model = model(repo); runCurrent(); model.cancel()
        repo.loadGate!!.complete(Unit); runCurrent(); assertTrue(model.state.value.finished); assertTrue(model.state.value.items.isEmpty())
        val another = Fake().apply { resolveGate = CompletableDeferred() }; val loaded = model(another); runCurrent(); loaded.read("small-key")
        val token = loaded.state.value.effect!!.id; var result: RssReadRecord? = another.latest
        val job = launch { result = loaded.resolve(token) }; runCurrent(); loaded.cancel(); another.resolveGate!!.complete(Unit); job.join(); assertNull(result)
    }
    @Test fun confirmedDeletionCannotBeCanceledOrDuplicatedAndStaleAckCannotCloseBrowser() = test {
        val repo = Fake().apply { clearGate = CompletableDeferred() }; val model = model(repo); runCurrent(); model.read("small-key"); val old = model.state.value.effect!!.id
        model.failed(old, "native failed"); model.browser("small-key"); val fresh = model.state.value.effect!!; model.delivered(old); assertEquals(fresh, model.state.value.effect)
        model.delivered(fresh.id); model.requestClear(); runCurrent(); model.confirmClear(); model.confirmClear(); model.cancel(); model.cancelClear(); runCurrent()
        assertFalse(model.state.value.finished); assertTrue(model.state.value.busy); assertEquals(1, repo.deletes)
        repo.clearGate!!.complete(Unit); runCurrent(); assertFalse(model.state.value.finished); assertTrue(model.state.value.items.isEmpty())
    }    @Test fun cancelledRestoredConfirmationIgnoresUncooperativeLateCountAndLoadingBlocksClear() = test {
        val repo = Fake().apply { countGate = CompletableDeferred(); ignoreCancellation = true }
        val saved = SavedStateHandle(mapOf("rssHistory.confirmClear" to true))
        val restored = model(repo, saved); runCurrent(); assertTrue(restored.state.value.loading)
        restored.cancelClear(); repo.countGate!!.complete(Unit); runCurrent()
        assertNull(restored.state.value.clearCount); assertFalse(saved.get<Boolean>("rssHistory.confirmClear")!!); assertEquals(0, repo.deletes)
        restored.requestClear(); runCurrent(); assertEquals(7, restored.state.value.clearCount)
        repo.loadGate = CompletableDeferred(); restored.load(); runCurrent(); assertTrue(restored.state.value.loading)
        restored.confirmClear(); assertEquals(0, repo.deletes)
        restored.cancelClear(); repo.loadGate!!.complete(Unit); runCurrent(); assertNull(restored.state.value.clearCount)
    }

}
