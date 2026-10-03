package io.legado.app.ui.widget.seekbar

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalSeekBarMigrationTest {

    @Test
    fun brightnessControlUsesComposeWithTheOriginalRangeAndAutoGate() {
        val screen =
            projectFile("src/main/java/io/legado/app/ui/book/read/ReadMenuScreen.kt").readText()
        assertTrue(screen.contains("LocalLayoutDirection provides LayoutDirection.Ltr"))
        assertTrue(screen.contains("enabled = !top.brightnessAutomatic"))
        assertTrue(screen.contains("valueRange = 0f..255f"))
        assertTrue(screen.contains(".verticalSlider()"))
        assertFalse(projectFile("src/main/res/layout/view_read_menu.xml").exists())
    }

    @Test
    fun obsoleteReflectiveSeekBarImplementationIsRemoved() {
        assertFalse(
            projectFile("src/main/java/io/legado/app/ui/widget/seekbar/VerticalSeekBar.kt").exists()
        )
        val attrs = projectFile("src/main/res/values/attrs.xml").readText()
        assertFalse(attrs.contains("name=\"VerticalSeekBar\""))
        assertFalse(attrs.contains("name=\"seekBarRotation\""))
    }

    @Test
    fun wrapperKeepsDirectionAndCompactMeasurementRules() {
        val source =
            projectFile("src/main/java/io/legado/app/ui/widget/seekbar/VerticalSeekBarWrapper.kt")
                .readText()
        val readMenu =
            projectFile("src/main/java/io/legado/app/ui/book/read/ReadMenu.kt").readText()

        assertTrue(source.contains("ViewCompat.LAYOUT_DIRECTION_LTR"))
        assertTrue(source.contains("MeasureSpec.makeMeasureSpec(contentHeight"))
        assertTrue(source.contains("child.measuredHeight + paddingLeft + paddingRight"))
        assertTrue(readMenu.contains("setScreenBrightness(it.toFloat())"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp")).firstOrNull {
            it.exists() || it.parentFile?.exists() == true
        } ?: File(pathInApp)
    }
}
