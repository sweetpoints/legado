package io.legado.app.ui.widget.code

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeViewPerformanceTest {

    @Test
    fun `syntax highlighting stays within the existing text limit`() {
        assertFalse(isCodeHighlightSupported(0))
        assertTrue(isCodeHighlightSupported(1))
        assertTrue(isCodeHighlightSupported(MAX_CODE_HIGHLIGHT_LENGTH))
        assertFalse(isCodeHighlightSupported(MAX_CODE_HIGHLIGHT_LENGTH + 1))
    }

    @Test
    fun `navigation keys stay inside the code editor`() {
        val codeView =
            File(
                    repositoryRoot(),
                    "app/src/main/java/io/legado/app/ui/widget/code/CodeView.kt",
                )
                .readText()
                .substringAfter("override fun dispatchKeyEvent")
                .substringBefore("override fun showDropDown")

        assertTrue(codeView.contains("if (super.dispatchKeyEvent(event)) return true"))
        assertTrue(codeView.contains("KeyEvent.KEYCODE_PAGE_UP"))
        assertTrue(codeView.contains("KeyEvent.KEYCODE_PAGE_DOWN"))
        assertTrue(codeView.contains("KeyEvent.KEYCODE_MOVE_HOME"))
        assertTrue(codeView.contains("KeyEvent.KEYCODE_MOVE_END"))
    }

    @Test
    fun `selection visibility follows the endpoint that changed`() {
        assertEquals(8, selectionVisibilityOffset(4, 4, 4, 8))
        assertEquals(2, selectionVisibilityOffset(4, 8, 2, 8))
        assertEquals(10, selectionVisibilityOffset(2, 8, 2, 10))
        assertEquals(6, selectionVisibilityOffset(2, 8, 6, 8))
        assertEquals(8, selectionVisibilityOffset(4, 10, 2, 8))
        assertEquals(8, selectionVisibilityOffset(-1, -1, 2, 8))
        assertEquals(7, selectionVisibilityOffset(7, 7, 7, 7))
        assertNull(selectionVisibilityOffset(2, 8, -1, -1))
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
