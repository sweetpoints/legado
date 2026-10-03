package io.legado.app.ui.widget.keyboard

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardToolRowsTest {

    @Test
    fun `Compose keyboard rows stay bounded while preference changes update immutable state`() {
        val screen =
            projectFile("src/main/java/io/legado/app/ui/code/CodeEditorScreen.kt").readText()
        val model =
            projectFile("src/main/java/io/legado/app/ui/code/CodeEditorComposeViewModel.kt")
                .readText()
        val host = projectFile("src/main/java/io/legado/app/ui/code/CodeEditActivity.kt").readText()
        assertTrue(host.contains("observeEvent<Int>(PreferKey.showBoardLine)"))
        assertTrue(host.contains("model.keyboardRows(it)"))
        assertTrue(model.contains("keyboardRows = rows.coerceIn(1, 5)"))
        assertTrue(screen.contains("GridCells.Fixed(rows)"))
        assertTrue(screen.contains("keyboardRows.coerceIn(1, 5)"))
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
}
