package io.legado.app.ui.book.bookmark

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AllBookmarksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<AllBookmarksViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class Fake : AllBookmarksRepository {
        val rows =
            MutableStateFlow(
                listOf(AllBookmarksRow(1, "A", "Author", "Chapter", "Original", "Note"))
            )
        var bookmark: Bookmark? =
            Bookmark(1, "A", "Author", 2, 17, "Chapter", "Full original", "Note")
        var gate: CompletableDeferred<Unit>? = null
        var nonCooperative = false
        var fail = false
        val exports = mutableListOf<Pair<String, Boolean>>()

        override fun observe() = rows

        override suspend fun resolve(id: Long, edit: Boolean): AllBookmarksDestination? {
            if (nonCooperative) withContext(NonCancellable) { gate?.await() } else gate?.await()
            if (fail) error("Resolve failed")
            return bookmark
                ?.takeIf { it.time == id }
                ?.let { AllBookmarksDestination(it.copy(), null) }
        }

        override suspend fun export(directory: String, markdown: Boolean): String {
            exports += directory to markdown
            gate?.await()
            if (fail) error("Export failed")
            return "bookmark.json"
        }
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        AllBookmarksViewModel(repo, saved).also { models += it }

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
    fun rowFlowKeepsOrderAndPendingTicketWhileDatabaseChanges() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        model.open(1, false)
        val effect = model.state.value.effect
        repo.rows.value = listOf(AllBookmarksRow(2, "B", "Other", "Second", "", ""))
        runCurrent()
        assertEquals(listOf(2L), model.state.value.rows.map { it.id })
        assertEquals(effect, model.state.value.effect)
    }

    @Test
    fun restoredOpenUsesLatestFullEntityAndDoesNotPutBodyIntoSavedState() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        first.open(1, true)
        first.open(1, false)
        val effect = first.state.value.effect!!
        first.stop()
        repo.bookmark = repo.bookmark!!.copy(bookText = "X".repeat(200000), chapterPos = 91)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals(effect, restored.state.value.effect)
        val destination = restored.resolveOpen(effect)!!
        assertEquals(91, destination.bookmark.chapterPos)
        assertEquals(200000, destination.bookmark.bookText.length)
        assertTrue(effect.edit)
        assertTrue(
            saved.keys().all {
                (saved.get<Any?>(it) as? String)?.length?.let { size -> size < 2000 } != false
            }
        )
        restored.delivered(effect.nonce)
        assertNull(restored.delivered(effect.nonce))
    }

    @Test
    fun deletedOpenTargetCanBeAcknowledgedAndAnotherActionStillWorks() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        model.open(1, false)
        val effect = model.state.value.effect!!
        repo.bookmark = null
        assertNull(model.resolveOpen(effect))
        model.delivered(effect.nonce)
        model.requestExport(true)
        assertEquals(AllBookmarksEffectType.Directory, model.state.value.effect!!.type)
    }

    @Test
    fun canceledNonCooperativeResolveKeepsSmallTicketForLaterResumedDelivery() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        model.open(1, false)
        val effect = model.state.value.effect!!
        repo.nonCooperative = true
        repo.gate = CompletableDeferred()
        var published = false
        val job = launch {
            model.resolveOpen(effect)
            published = true
        }
        runCurrent()
        job.cancel()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertFalse(published)
        assertEquals(effect, model.state.value.effect)
    }

    @Test
    fun directoryResultNeedsOutstandingTicketAndRestoresMarkdownAcrossProcessCreation() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        first.directoryResult("unexpected")
        assertTrue(repo.exports.isEmpty())
        first.requestExport(true)
        first.delivered(first.state.value.effect!!.nonce)
        first.stop()
        val restored = model(repo, copy(saved))
        runCurrent()
        restored.directoryResult("content://directory")
        restored.directoryResult("duplicate")
        runCurrent()
        assertEquals(listOf("content://directory" to true), repo.exports)
        assertEquals(AllBookmarksEffectType.Exported, restored.state.value.effect!!.type)
    }

    @Test
    fun cancelDirectoryNeverWritesAndExportFailureCanStartFreshRequest() = test {
        val repo = Fake()
        val model = model(repo)
        runCurrent()
        model.requestExport(false)
        model.delivered(model.state.value.effect!!.nonce)
        model.directoryResult(null)
        assertTrue(repo.exports.isEmpty())
        model.requestExport(false)
        model.delivered(model.state.value.effect!!.nonce)
        repo.fail = true
        model.directoryResult("file://directory")
        runCurrent()
        assertFalse(model.state.value.exporting)
        assertNotNull(model.state.value.error)
        repo.fail = false
        model.requestExport(true)
        model.delivered(model.state.value.effect!!.nonce)
        model.directoryResult("retry")
        runCurrent()
        assertEquals(2, repo.exports.size)
        assertEquals(AllBookmarksEffectType.Exported, model.state.value.effect!!.type)
    }

    @Test
    fun pendingNativeFailureDoesNotReplayAndWrongNonceCannotConsumeEffect() = test {
        val model = model(Fake())
        runCurrent()
        model.open(1, true)
        val effect = model.state.value.effect!!
        assertNull(model.delivered("other"))
        model.delivered(effect.nonce)
        model.deliveryFailed(effect, "Native failed")
        assertNull(model.state.value.effect)
        assertEquals("Native failed", model.state.value.error)
        model.clearError()
        assertNull(model.state.value.error)
    }

    @Test
    fun smallScrollStateRestoresAndStopPreventsLateExportCompletion() = test {
        val repo = Fake()
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        runCurrent()
        model.scroll(7, 31)
        model.requestExport(false)
        model.delivered(model.state.value.effect!!.nonce)
        repo.gate = CompletableDeferred()
        model.directoryResult("directory")
        runCurrent()
        model.stop()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertNull(model.state.value.effect)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals(7, restored.state.value.scroll)
        assertEquals(31, restored.state.value.offset)
    }
}
