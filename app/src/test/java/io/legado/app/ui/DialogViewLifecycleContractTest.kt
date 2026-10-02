package io.legado.app.ui

import io.legado.app.ui.book.read.ContentDraftState
import io.legado.app.ui.book.read.ContentEditTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DialogViewLifecycleContractTest {

    @Test
    fun `dialog data loaders are cancelled with their views`() {
        val search = source("book/search/SearchScopeDialog.kt")

        val initData = search.section("private fun initData()", "@SuppressLint")
        val upBookSource = search.section("private fun upBookSource", "inner class RecyclerAdapter")
        assertTrue(initData.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertTrue(upBookSource.contains("viewLifecycleOwner.lifecycleScope.launch"))
        assertTrue(upBookSource.contains("viewLifecycleOwner.lifecycle"))
        assertFalse(upBookSource.contains("\n        sourceFlowJob = lifecycleScope.launch"))
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

    private fun source(relativePath: String): String {
        return projectFile("src/main/java/io/legado/app/ui/$relativePath")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun String.section(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) {
            "Missing section $startMarker .. $endMarker"
        }
        return substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
