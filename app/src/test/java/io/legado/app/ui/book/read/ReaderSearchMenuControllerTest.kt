package io.legado.app.ui.book.read

import io.legado.app.ui.book.searchContent.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSearchMenuControllerTest {
    @Test
    fun emptyNavigationCannotIndexMissingResults() {
        val controller = ReaderSearchMenuController()
        controller.index(5)
        assertNull(controller.navigate(1))
        assertNull(controller.state.value.selected)
        assertEquals(-1, controller.state.value.currentIndex)
    }

    @Test
    fun navigationClampsAtBothEdgesAndRetainsPreviousResult() {
        val controller = ReaderSearchMenuController()
        val first = SearchResult(query = "first")
        val second = SearchResult(query = "second")
        controller.results(listOf(first, second))
        assertEquals(first to 0, controller.navigate(-1))
        assertNull(controller.state.value.previous)
        assertEquals(second to 1, controller.navigate(1))
        assertEquals(first, controller.state.value.previous)
        assertEquals(second to 1, controller.navigate(1))
        assertEquals(first to 0, controller.navigate(-9))
    }

    @Test
    fun resultSnapshotsDoNotShareMutableListsAndRemovedIndexesAreSafe() {
        val controller = ReaderSearchMenuController()
        val list = mutableListOf(SearchResult(), SearchResult())
        controller.results(list)
        controller.index(1)
        list.clear()
        assertEquals(2, controller.state.value.results.size)
        controller.results(emptyList())
        assertNull(controller.state.value.selected)
        assertNull(controller.state.value.previous)
    }

    @Test
    fun interruptedExitCannotHideReopenedPanelAndDuplicateExitIsIgnored() {
        val controller = ReaderSearchMenuController()
        controller.show()
        val first = controller.hide()!!
        assertNull(controller.hide())
        assertTrue(controller.state.value.panelVisible)
        controller.show()
        assertFalse(controller.hidden(first))
        assertTrue(controller.state.value.visible)
        val second = controller.hide()!!
        assertTrue(second > first)
        assertFalse(controller.hidden(first))
        assertTrue(controller.hidden(second))
        assertFalse(controller.hidden(second))
        assertFalse(controller.state.value.panelVisible)
        assertTrue(controller.state.value.navigationVisible)
    }

    @Test
    fun leavingSearchHidesBothNavigationAndPanelAndInvalidatesQueuedCompletion() {
        val controller = ReaderSearchMenuController()
        controller.results(listOf(SearchResult(query = "retained")))
        controller.index(0)
        controller.show()
        val queuedExit = controller.hide()!!

        controller.deactivate()
        assertFalse(controller.state.value.navigationVisible)
        assertFalse(controller.state.value.panelVisible)
        assertFalse(controller.hidden(queuedExit))
        assertEquals("retained", controller.state.value.selected!!.query)

        controller.show()
        val currentExit = controller.hide()!!
        assertFalse(controller.hidden(queuedExit))
        assertTrue(controller.hidden(currentExit))
    }

    @Test
    fun chapterAndCurrentResultsRefreshWithoutResettingTheSelection() {
        val controller = ReaderSearchMenuController()
        controller.results(listOf(SearchResult(query = "kept")))
        controller.index(0)
        controller.chapter("Chapter 2")
        controller.show()
        assertEquals("Chapter 2", controller.state.value.chapterTitle)
        assertEquals("kept", controller.state.value.selected!!.query)
    }
}
