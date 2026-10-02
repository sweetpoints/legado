package io.legado.app.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangeCoverRepositoryTest {
    private val target = ChangeCoverTarget("Name", "Author")
    @Test fun cacheThresholdPreservesDefaultAndEnabledCacheOrderWithoutUnnecessaryAutomaticSearch() = runTest {
        val store = Fake(); val repo = DefaultChangeCoverRepository(store, io = StandardTestDispatcher(testScheduler))
        for (size in 0..3) { store.cached = (0 until size).map { item("$it", it) }
            val result = repo.initial("session", target)
            assertEquals(size <= 1, result.autoSearch); assertEquals("use_default_cover", result.snapshot.covers.first().coverUrl)
            assertEquals(store.cached, result.snapshot.covers.drop(1))
        }
    }
    @Test fun ruleWinsAndResumeSearchesSourcesWithoutRepeatingRuleOrClearingRuleResult() = runTest {
        val store = Fake().apply { rule = "rule-cover" }; val repo = DefaultChangeCoverRepository(store, io = StandardTestDispatcher(testScheduler))
        val initial = repo.initial("session", target).snapshot
        val paused = repo.search(initial, false).toList().last()
        assertEquals(ChangeCoverStatus.RuleReady, paused.status); assertEquals(listOf("default", "rule"), paused.covers.map { it.id })
        assertTrue(store.searched.isEmpty()); assertEquals(1, store.ruleCalls)
        val completed = repo.search(paused, true).toList().last()
        assertEquals(ChangeCoverStatus.Idle, completed.status); assertTrue(completed.pending.isEmpty())
        assertEquals(listOf("default", "rule", "book:b", "book:a"), completed.covers.map { it.id }); assertEquals(1, store.ruleCalls)
    }
    @Test fun refreshClearsOldResultsAndSearchesAgainWhereResumeUsesOnlyUnfinishedSourceIdentities() = runTest {
        val store = Fake(); val repo = DefaultChangeCoverRepository(store, io = StandardTestDispatcher(testScheduler))
        val old = ChangeCoverSnapshot(target, listOf(item("old", -100)), listOf("a"), ChangeCoverStatus.Running)
        val resumed = repo.search(old, true).toList().last()
        assertEquals(listOf("a"), store.searched); assertTrue(resumed.covers.any { it.id == "book:old" })
        store.searched.clear(); val refreshed = repo.search(old, false).toList().last()
        assertEquals(setOf("a", "b"), store.searched.toSet()); assertFalse(refreshed.covers.any { it.id == "book:old" })
    }
    @Test fun duplicateBookIdentityDoesNotDuplicateGridAndIndependentSourceFailureDoesNotAbortOthers() = runTest {
        val store = Fake().apply { ids = listOf("a", "b", "failed"); sameBook = true }
        val repo = DefaultChangeCoverRepository(store, io = StandardTestDispatcher(testScheduler))
        val last = repo.search(repo.initial("session", target).snapshot, false).toList().last()
        assertEquals(2, last.covers.size); assertEquals(ChangeCoverStatus.Idle, last.status); assertTrue(last.pending.isEmpty())
        assertEquals("封面换源搜索出错", store.logged.single())
    }
    @Test fun concurrentSearchIsBoundedAndCancellationStopsRuleAndAllWorkers() = runTest {
        val store = Fake().apply { ids = (1..20).map { it.toString() }; gate = CompletableDeferred() }
        val repo = DefaultChangeCoverRepository(store, concurrency = 3, io = StandardTestDispatcher(testScheduler))
        val job = launch { repo.search(ChangeCoverSnapshot(target, emptyList()), false).collect() }
        runCurrent(); assertEquals(3, store.active); assertEquals(3, store.maxActive)
        job.cancelAndJoin(); assertEquals(0, store.active)
        store.ruleGate = CompletableDeferred(); store.gate = null
        val ruleJob = launch { repo.search(ChangeCoverSnapshot(target, emptyList()), false).collect() }
        runCurrent(); assertTrue(store.ruleActive); ruleJob.cancelAndJoin(); assertFalse(store.ruleActive)
    }
    @Test fun timeoutOnlySkipsThatSourceAndRuleFailureFallsBackToSourceSearch() = runTest {
        val store = Fake().apply { ruleFails = true; gate = CompletableDeferred() }
        val repo = DefaultChangeCoverRepository(store, timeoutMillis = 50, io = StandardTestDispatcher(testScheduler))
        val result = repo.search(ChangeCoverSnapshot(target, emptyList()), false).toList().last()
        assertEquals(ChangeCoverStatus.Idle, result.status); assertEquals(1, result.covers.size); assertEquals(0, store.active)
        assertEquals(listOf("封面规则搜索出错"), store.logged)
    }
    @Test fun persistedSessionRestoresPendingWorkAndRulePauseWithoutRoomReloadOrLargeSavedState() = runTest {
        val store = Fake(); val repo = DefaultChangeCoverRepository(store, io = StandardTestDispatcher(testScheduler))
        val snapshot = ChangeCoverSnapshot(target, listOf(item("saved", 0)), listOf("b"), ChangeCoverStatus.Running, 10)
        repo.save("session", snapshot); val restored = repo.initial("session", target)
        assertEquals(snapshot, restored.snapshot); assertTrue(restored.autoSearch); assertEquals(0, store.cacheCalls)
        repo.save("session", snapshot.copy(status = ChangeCoverStatus.RuleReady))
        assertFalse(repo.initial("session", target).autoSearch)
        store.saved = snapshot.copy(target = ChangeCoverTarget("Other", "Other")); assertEquals(1, repo.initial("session", target).snapshot.covers.size)
    }
    @Test fun selectedReceiptLoadsExactLargeUrlFromDurableSessionAndMissingReceiptFailsWithoutBlankCallback() = runTest {
        val store = Fake(); val repo = DefaultChangeCoverRepository(store, io = StandardTestDispatcher(testScheduler))
        val huge = "data:image/png;base64," + "A".repeat(1200000)
        val snapshot = ChangeCoverSnapshot(target, listOf(item("selected", 0).copy(coverUrl = huge)), revision = 10)
        repo.save("receipt", snapshot); assertEquals(huge, repo.selected("receipt", "book:selected"))
        assertTrue(runCatching { repo.selected("receipt", "missing") }.isFailure)
    }
    private fun item(id: String, order: Int) = ChangeCoverItem("book:$id", id, id, "cover:$id", order)
    private inner class Fake : ChangeCoverStore {
        var cached = emptyList<ChangeCoverItem>(); var cacheCalls = 0; var saved: ChangeCoverSnapshot? = null
        var ids = listOf("a", "b"); var rule: String? = null; var ruleCalls = 0; var ruleFails = false; var sameBook = false
        var gate: CompletableDeferred<Unit>? = null; var ruleGate: CompletableDeferred<Unit>? = null
        var active = 0; var maxActive = 0; var ruleActive = false
        val searched = mutableListOf<String>(); val logged = mutableListOf<String>()
        override suspend fun cached(target: ChangeCoverTarget): List<ChangeCoverItem> { cacheCalls++; return cached }
        override suspend fun sources() = ids
        override suspend fun rule(target: ChangeCoverTarget): String? { ruleCalls++; ruleActive = true
            try { ruleGate?.await(); if (ruleFails) error("rule"); return rule } finally { ruleActive = false } }
        override suspend fun search(target: ChangeCoverTarget, source: String): ChangeCoverItem? {
            active++; maxActive = maxOf(maxActive, active); searched += source
            try { gate?.await(); if (source == "failed") error("source"); return item(if (sameBook) "shared" else source, if (source == "b") 0 else 1) }
            finally { active-- }
        }
        override suspend fun read(session: String) = saved
        override suspend fun write(session: String, snapshot: ChangeCoverSnapshot) { saved = snapshot }
        override fun log(message: String, error: Throwable) { logged += message }
    }
}
