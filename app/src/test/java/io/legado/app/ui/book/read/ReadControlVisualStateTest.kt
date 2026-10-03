package io.legado.app.ui.book.read

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadControlVisualStateTest {

    @Test
    fun chapterNavigationKeepsDisabledTextVisibleOnTheReaderBackground() {
        val source =
            readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadMenuBottomScreen.kt")
        assertTrue(source.contains("foreground.copy(alpha = .4f)"))
        assertTrue(source.contains("state.previousEnabled"))
        assertTrue(source.contains("state.nextEnabled"))
    }

    @Test
    fun runningReadAloudOpensControlsInsteadOfTogglingPlayback() {
        val source =
            readProjectFile("src/main/java/io/legado/app/ui/book/read/ReaderMenuController.kt")
        val clickBlock =
            source
                .substringAfter("ReadMenuAction.ReadAloud ->")
                .substringBefore("ReadMenuAction.ReadAloudSettings ->")

        assertTrue(clickBlock.contains("if (BaseReadAloudService.isRun)"))
        assertTrue(clickBlock.contains("callBack.showReadAloudDialog()"))
        assertTrue(clickBlock.contains("else"))
        assertTrue(clickBlock.contains("callBack.onClickReadAloud()"))
    }

    private fun readProjectFile(pathInApp: String): String = projectFile(pathInApp).readText()

    private fun projectFile(pathInApp: String): File =
        sequenceOf(
                File(pathInApp),
                File("app/$pathInApp"),
            )
            .first(File::isFile)
}
