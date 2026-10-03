package io.legado.app.ui.book.read

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextActionMenuSourceTest {

    @Test
    fun `selection popup preserves all native coordinate branches and exact threshold`() {
        assertEquals(
            TextPopupPosition(TextPopupEdge.Bottom, 10, 400),
            textActionPopupPosition(1200, 10, 800, 820, 40, 860),
        )
        assertEquals(
            TextPopupPosition(TextPopupEdge.Top, 10, 100),
            textActionPopupPosition(1200, 10, 20, 100, 40, 601),
        )
        assertEquals(
            TextPopupPosition(TextPopupEdge.Top, 40, 600),
            textActionPopupPosition(1200, 10, 500, 100, 40, 600),
        )
        assertEquals(
            TextPopupPosition(TextPopupEdge.Top, 40, 400),
            textActionPopupPosition(1200, 10, 20, 100, 40, 400),
        )
    }

    @Test
    fun `reader popups use the popup window coordinate height`() {
        val source =
            projectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt").readText()

        assertEquals(1, Regex("window\\.decorView\\.height").findAll(source).count())
        assertTrue(source.contains("textActionMenu.show(\n            readView,"))
        assertFalse(source.contains("readView.height +"))
        val contextPopup =
            projectFile("src/main/java/io/legado/app/ui/book/read/ReaderContextMenuScreen.kt")
                .readText()
        assertTrue(contextPopup.contains("anchorBounds.top + state.y.roundToInt()"))
        assertTrue(contextPopup.contains("windowSize.height - contentSize.height"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp")).firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
