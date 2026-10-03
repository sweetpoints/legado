package io.legado.app.ui.book.read

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadControlVisualStateTest {

    @Test
    fun chapterNavigationKeepsDisabledTextVisibleOnTheReaderBackground() {
        val source = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadMenu.kt")

        assertTrue(source.contains(".setDisabledColor(ColorUtils.withAlpha(textColor, 0.4f))"))
        assertTrue(source.contains("tvPre.setTextColor(chapterTextColor)"))
        assertTrue(source.contains("tvNext.setTextColor(chapterTextColor)"))
    }

    @Test
    fun runningReadAloudOpensControlsInsteadOfTogglingPlayback() {
        val source = readProjectFile("src/main/java/io/legado/app/ui/book/read/ReadMenu.kt")
        val clickBlock =
            source
                .substringAfter("llReadAloud.setOnClickListener")
                .substringBefore("llReadAloud.onLongClick")

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
