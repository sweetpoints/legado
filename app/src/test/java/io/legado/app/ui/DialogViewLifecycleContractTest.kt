package io.legado.app.ui

import io.legado.app.ui.book.read.ContentDraftState
import io.legado.app.ui.book.read.ContentEditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

class DialogViewLifecycleContractTest {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `search scope stops collecting when the dialog view pauses or is destroyed`() = kotlinx.coroutines.test.runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        var collectors = 0
        val repository = object : io.legado.app.data.repository.SearchScopeRepository {
            override suspend fun groups() = emptyList<String>()
            override fun sources(query: String) = kotlinx.coroutines.flow.flow<List<io.legado.app.data.repository.SearchScopeSource>> {
                collectors++
                try { emit(emptyList()); kotlinx.coroutines.awaitCancellation() } finally { collectors-- }
            }
        }
        val model = io.legado.app.ui.book.search.SearchScopeViewModel(repository, androidx.lifecycle.SavedStateHandle())
        val store = androidx.lifecycle.ViewModelStore().apply { put("search", model) }
        try {
            model.tab(io.legado.app.ui.book.search.SearchScopeTab.Sources)
            model.setActive(true); testScheduler.runCurrent(); assertEquals(1, collectors)
            model.setActive(false); testScheduler.runCurrent(); assertEquals(0, collectors)
            model.setActive(true); testScheduler.runCurrent(); assertEquals(1, collectors)
            store.clear(); testScheduler.runCurrent(); assertEquals(0, collectors)
        } finally { store.clear(); kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test
    fun `content editor target does not follow the global reader chapter`() {
        val target = ContentEditTarget("book-a", chapterIndex = 3, chapterPos = 120)

        assertTrue(target.matches("book-a", 3))
        assertFalse(target.matches("book-a", 4))
        assertFalse(target.matches("book-b", 3))
    }

    @Test
    fun `newer content request invalidates an older result`() {
        val state = ContentDraftState()
        state.restore("original")
        val older = state.newRequest()
        val newer = state.newRequest()

        assertNull(state.applyLoaded(older, "older content"))
        assertEquals("newer content", state.applyLoaded(newer, "newer content"))
        assertEquals("newer content", state.text)
    }

    @Test
    fun `stale content result does not replace an edited draft`() {
        val state = ContentDraftState()
        state.restore("original")
        val request = state.newRequest()

        state.update("edited draft")

        assertNull(state.applyLoaded(request, "loaded content"))
        assertEquals("edited draft", state.text)
    }

    @Test
    fun `content result applies when the draft has not changed`() {
        val state = ContentDraftState()
        state.restore("edited draft")
        val request = state.newRequest()

        assertEquals("reset content", state.applyLoaded(request, "reset content"))
        assertEquals("reset content", state.text)
        assertFalse(state.hasChanges)
    }

    @Test
    fun `content draft only changes after a real edit`() {
        val state = ContentDraftState()
        state.restore("loaded content")

        assertFalse(state.hasChanges)

        state.update("edited content")
        assertTrue(state.hasChanges)

        state.update("loaded content")
        assertFalse(state.hasChanges)
    }

    @Test
    fun `editing before content loads is still a change`() {
        val state = ContentDraftState()

        state.update("early edit")

        assertTrue(state.hasChanges)
    }

    @Test
    fun `restored dirty draft remains changed`() {
        val state = ContentDraftState()

        state.restore("restored edit", hasChanges = true)

        assertTrue(state.hasChanges)
    }

    @Test
    fun `restored draft is kept as the authoritative text`() {
        val state = ContentDraftState()

        assertTrue(state.restore("restored draft"))
        assertFalse(state.restore("older framework state"))

        assertEquals("restored draft", state.text)
        assertTrue(state.hasDraft)
    }

}
