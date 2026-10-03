package io.legado.app.ui.rss.favorites

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssFavoriteConfigViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<RssFavoriteConfigViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class Fake(
        var draft: RssFavoriteConfigDraft = RssFavoriteConfigDraft("Original", "Group")
    ) : RssFavoriteConfigRepository {
        var loadGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var uncooperative = false
        var fail = false
        var loads = 0
        var writes = 0

        override suspend fun load(id: String): RssFavoriteConfigDraft {
            loads++
            if (uncooperative) withContext(NonCancellable) { loadGate?.await() }
            else loadGate?.await()
            if (fail) error("failed")
            return draft.copy()
        }

        override suspend fun write(id: String, draft: RssFavoriteConfigDraft) {
            writes++
            writeGate?.await()
            if (fail) error("failed")
            if (draft.revision >= this.draft.revision) this.draft = draft.copy()
        }
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        RssFavoriteConfigViewModel(repo, saved, "id").also { models += it }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                runCurrent()
            }
        }

    @Test
    fun pendingLoadBlocksEditingAndFailureAllowsRetry() = test {
        val repo =
            Fake().apply {
                loadGate = CompletableDeferred()
                fail = true
            }
        val model = model(repo)
        runCurrent()
        model.title("Ignored", 0, 0)
        model.confirm()
        model.delete()
        assertEquals(0, repo.writes)
        repo.loadGate!!.complete(Unit)
        runCurrent()
        assertEquals("failed", model.state.value.error)
        repo.fail = false
        model.load()
        runCurrent()
        assertEquals("Original", model.state.value.title)
        assertTrue(model.state.value.canEdit)
    }

    @Test
    fun debouncedDraftAndSelectionRestoreWithoutBundlingLargeTitle() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        val large = "Large title ".repeat(10000)
        first.title(large, 20, 5)
        first.group("New group", 3, 3)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(1, repo.writes)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals(large, restored.state.value.title)
        assertEquals(20, restored.state.value.titleStart)
        assertEquals(5, restored.state.value.titleEnd)
        assertEquals(3, restored.state.value.groupStart)
        assertTrue(
            saved
                .keys()
                .mapNotNull { saved.get<Any?>(it) }
                .filterIsInstance<String>()
                .all { it.length < 100 }
        )
    }

    @Test
    fun blankFieldsKeepNullableOriginalsAndNonBlankWhitespaceIsPreservedExactly() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        model.title(" ", 1, 1)
        model.group(" Edited ", 1, 4)
        model.confirm()
        runCurrent()
        val effect = model.consume()!!
        assertEquals("Original", effect.title)
        assertEquals(" Edited ", effect.group)
        assertEquals(RssFavoriteConfigAction.Save, effect.action)
        val nullable = model(Fake(RssFavoriteConfigDraft(null, null)))
        runCurrent()
        nullable.confirm()
        runCurrent()
        val blank = nullable.consume()!!
        assertNull(blank.title)
        assertNull(blank.group)
    }

    @Test
    fun pendingCallbackSurvivesRestorationAndConsumptionPreventsReplay() = test {
        val repo = Fake()
        val first = model(repo)
        runCurrent()
        first.title("Edited", 6, 6)
        first.confirm()
        runCurrent()
        val saved = SavedStateHandle()
        val restored = model(repo, saved)
        runCurrent()
        val pending = restored.state.value.effect!!
        assertEquals("Edited", pending.title)
        assertEquals(RssFavoriteConfigAction.Save, pending.action)
        assertEquals(pending, restored.consume())
        assertNull(restored.consume())
        val again = model(repo, copy(saved))
        runCurrent()
        assertTrue(again.state.value.finished)
        assertEquals(2, repo.loads)
    }

    @Test
    fun deleteIsDistinctAndDuplicateButtonsCannotQueueTwoActions() = test {
        val repo = Fake().apply { writeGate = CompletableDeferred() }
        val model = model(repo)
        runCurrent()
        model.delete()
        model.delete()
        model.confirm()
        model.cancel()
        model.title("Ignored", 0, 0)
        runCurrent()
        assertEquals(1, repo.writes)
        assertTrue(model.state.value.busy)
        assertFalse(model.state.value.finished)
        repo.writeGate!!.complete(Unit)
        runCurrent()
        assertEquals(RssFavoriteConfigAction.Delete, model.consume()!!.action)
    }

    @Test
    fun cancelDoesNotProduceCallbackAndLateLoadCannotReplaceClosedState() = test {
        val repo =
            Fake().apply {
                loadGate = CompletableDeferred()
                uncooperative = true
            }
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        first.cancel()
        repo.loadGate!!.complete(Unit)
        runCurrent()
        assertTrue(first.state.value.finished)
        assertFalse(first.state.value.loaded)
        assertNull(first.consume())
        assertEquals(0, repo.writes)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertTrue(restored.state.value.finished)
        assertEquals(1, repo.loads)
    }

    @Test
    fun failedCommitKeepsDraftRetryableAndOnStopFlushSavesLatestWithoutWaitingForDebounce() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        model.title("Edited", 6, 6)
        model.flushDraft()
        assertEquals("Edited", repo.draft.title)
        repo.fail = true
        model.confirm()
        runCurrent()
        assertEquals("failed", model.state.value.error)
        assertTrue(model.state.value.canEdit)
        repo.fail = false
        model.confirm()
        runCurrent()
        assertEquals("Edited", model.consume()!!.title)
    }

    @Test
    fun explicitReloadCannotOverwriteUserDraftAndRestoredSelectionIsClamped() = test {
        val repo = Fake()
        val saved =
            SavedStateHandle(mapOf("rssFavorite.titleStart" to 5000, "rssFavorite.titleEnd" to -10))
        val model = model(repo, saved)
        runCurrent()
        assertEquals(8, model.state.value.titleStart)
        assertEquals(0, model.state.value.titleEnd)
        model.title("Draft", 5, 5)
        model.load()
        runCurrent()
        assertEquals("Draft", model.state.value.title)
        assertEquals(1, repo.loads)
    }
}
