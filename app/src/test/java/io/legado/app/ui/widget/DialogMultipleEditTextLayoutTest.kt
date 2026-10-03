package io.legado.app.ui.widget

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class DialogMultipleEditTextLayoutTest {
    @Test
    fun retiredMultipleFieldDialogLayoutHasNoFileOrRuntimeConsumer() {
        val layout =
            listOf(
                    File("src/main/res/layout/dialog_multiple_edit_text.xml"),
                    File("app/src/main/res/layout/dialog_multiple_edit_text.xml"),
                )
                .firstOrNull(File::exists)
        assertFalse(layout?.isFile == true)

        val production =
            File("src/main/java").takeIf(File::isDirectory) ?: File("app/src/main/java")
        production.walkTopDown().filter(File::isFile).forEach { source ->
            assertFalse(
                "${source.path} still inflates the retired dialog",
                source.readText().contains("dialog_multiple_edit_text"),
            )
        }
    }
}
