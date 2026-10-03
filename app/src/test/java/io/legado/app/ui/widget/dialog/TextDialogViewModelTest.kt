package io.legado.app.ui.widget.dialog

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextDialogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<TextDialogViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class Fake(
        var request: TextDialogRequest =
            TextDialogRequest("Help", "## One\nAlpha Alpha\n## Two\nBeta", "MD", showToc = true)
    ) : TextDialogRequestRepository {
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var loads = 0

        override suspend fun load(id: String): TextDialogRequest {
            loads++
            gate?.await()
            if (fail) error("request failed")
            return request
        }
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        TextDialogViewModel(repo, saved, "id", { dispatcher.scheduler.currentTime }, dispatcher)
            .also { models += it }

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
    fun pendingRequestCannotExposeContentOrAllowInteractionsBeforeDiskWriteCompletes() = test {
        val repo = Fake().apply { gate = CompletableDeferred() }
        val model = model(repo)
        runCurrent()
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.request)
        model.edit()
        model.toggleSearch()
        assertFalse(model.state.value.editPending)
        repo.gate!!.complete(Unit)
        runCurrent()
        assertFalse(model.state.value.loading)
        assertEquals(2, model.state.value.sections.size)
    }

    @Test
    fun requestFailureIsRetryableAndDoesNotInventAnEmptyDocument() = test {
        val repo = Fake().apply { fail = true }
        val model = model(repo)
        runCurrent()
        assertEquals("request failed", model.state.value.error)
        assertNull(model.state.value.request)
        repo.fail = false
        model.load()
        runCurrent()
        assertEquals(2, repo.loads)
        assertNull(model.state.value.error)
    }

    @Test
    fun sectionSelectionAndGlobalSearchKeepOriginalHelpersAndClearEachOther() = test {
        val model = model(Fake())
        runCurrent()
        model.section(2)
        runCurrent()
        assertTrue(model.state.value.document.text.contains("Beta"))
        assertFalse(model.state.value.document.text.contains("Alpha"))
        model.toggleSearch()
        model.query("alpha")
        runCurrent()
        assertEquals(0, model.state.value.selectedSection)
        assertEquals(2, model.state.value.matches.size)
        model.moveMatch(-1)
        assertEquals(1, model.state.value.matchIndex)
        model.moveMatch(1)
        assertEquals(0, model.state.value.matchIndex)
        model.section(1)
        runCurrent()
        assertEquals("", model.state.value.query)
        assertTrue(model.state.value.matches.isEmpty())
    }

    @Test
    fun literalCaseInsensitiveRenderedSearchExcludesMarkdownDelimitersAndHtmlTags() = test {
        val model =
            model(
                Fake(
                    TextDialogRequest(
                        "Help",
                        "## One\n**A.b** <b>a.B</b>\n## Two\nRest",
                        "MD",
                        showToc = true,
                    )
                )
            )
        runCurrent()
        model.query("a.b")
        runCurrent()
        assertEquals(2, model.state.value.matches.size)
        assertFalse(model.state.value.document.text.contains("<b>"))
        model.query("**")
        runCurrent()
        assertTrue(model.state.value.matches.isEmpty())
        assertEquals(-1, model.state.value.matchIndex)
    }

    @Test
    fun staleScrollAckCannotConsumeTheNextRequestAndRestorationKeepsQueryAndMatchIndex() = test {
        val saved = SavedStateHandle()
        val repo = Fake()
        val model = model(repo, saved)
        runCurrent()
        model.query("Alpha")
        runCurrent()
        val old = model.state.value.scroll!!
        model.moveMatch(1)
        val fresh = model.state.value.scroll!!
        model.scrolled(old.id, 10)
        assertEquals(fresh, model.state.value.scroll)
        model.scrolled(fresh.id, 40)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals("Alpha", restored.state.value.query)
        assertEquals(1, restored.state.value.matchIndex)
        assertTrue(restored.state.value.scroll!!.id > fresh.id)
    }

    @Test
    fun plainScrollRestoresWithoutBundlingLargeContent() = test {
        val repo = Fake(TextDialogRequest("Log", "x".repeat(100000)))
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        runCurrent()
        model.scrolled(model.state.value.scroll!!.id, 450)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals(450, restored.state.value.scroll!!.y)
        assertTrue(saved.keys().none { it.contains("content") })
        assertTrue(
            saved
                .keys()
                .mapNotNull { saved.get<Any?>(it) }
                .filterIsInstance<String>()
                .none { it.length > 1000 }
        )
    }

    @Test
    fun countdownUsesPersistentDeadlineAndAutomaticCloseDoesNotRestartOnRecreation() = test {
        val repo = Fake(TextDialogRequest("Timed", "Text", time = 5000, autoClose = true))
        val saved = SavedStateHandle()
        val first = model(repo, saved)
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        val restored = model(repo, copy(saved))
        runCurrent()
        first.stop()
        assertEquals(3000L, restored.state.value.remaining)
        assertFalse(restored.state.value.canCancel)
        advanceTimeBy(3000)
        runCurrent()
        assertTrue(restored.state.value.finished)
    }

    @Test
    fun countdownUnlocksCancellationWithoutAutoCloseAndCloseMenuCanAlwaysFinish() = test {
        val repo = Fake(TextDialogRequest("Timed", "Text", time = 2000))
        val model = model(repo)
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertTrue(model.state.value.canCancel)
        assertFalse(model.state.value.finished)
        val manual = model(Fake(TextDialogRequest("Timed", "Text", time = 5000)))
        runCurrent()
        manual.close()
        assertTrue(manual.state.value.finished)
    }

    @Test
    fun editorNavigationIsConsumedExactlyOnceAndUsesFullUntruncatedSource() = test {
        val content = "x".repeat(100000)
        val repo = Fake(TextDialogRequest("Log", content))
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        runCurrent()
        assertTrue(model.state.value.document.text.length < content.length)
        model.edit()
        val restoredSaved = copy(saved)
        val restored = model(repo, restoredSaved)
        runCurrent()
        assertEquals(content, restored.consumeEdit()!!.content)
        assertNull(restored.consumeEdit())
        val acknowledged = model(repo, copy(restoredSaved))
        runCurrent()
        assertNull(acknowledged.consumeEdit())
        assertFalse(model.state.value.finished)
    }

    @Test
    fun nonHelpModesNeverExposeSearchOrTocAndFinishedRestoreDoesNotLoadAgain() = test {
        val repo = Fake(TextDialogRequest("HTML", "<b>Text</b>", "HTML", showToc = true))
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        runCurrent()
        model.toggleSearch()
        model.toc(true)
        model.query("Text")
        assertFalse(model.state.value.searchVisible)
        assertFalse(model.state.value.tocVisible)
        assertEquals("", model.state.value.query)
        model.close()
        val restored = model(repo, copy(saved))
        runCurrent()
        assertTrue(restored.state.value.finished)
        assertEquals(1, repo.loads)
    }
}
