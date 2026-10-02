package io.legado.app.ui.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceEditInputTouchTargetTest {

    @Test
    fun `shared source editor input has an accessible touch target`() {
        val layout = readProjectFile("src/main/res/layout/item_source_edit.xml")

        assertTrue(layout.contains("android:minHeight=\"48dp\""))
        assertTrue(layout.contains("android:paddingHorizontal=\"12dp\""))
        assertTrue(layout.contains("android:paddingTop=\"12dp\""))
        assertTrue(layout.contains("android:paddingBottom=\"12dp\""))
        assertFalse(layout.contains("TouchTargetSizeCheck"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
