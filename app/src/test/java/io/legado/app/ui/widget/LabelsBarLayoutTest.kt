package io.legado.app.ui.widget

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelsBarLayoutTest {

    @Test
    fun `labels use flexbox and logical spacing`() {
        val source =
            File(
                    repositoryRoot,
                    "app/src/main/java/io/legado/app/ui/widget/LabelsBar.kt",
                )
                .readText()

        assertTrue(source.contains(": FlexboxLayout(context, attrs)"))
        assertTrue(source.contains("FlexboxLayout.LayoutParams("))
        assertTrue(source.contains("marginEnd ="))
        assertTrue(source.contains("flexWrap == FlexWrap.NOWRAP"))
        assertFalse(source.contains("setMargins("))
        assertFalse(source.contains("rightMargin"))
    }

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
