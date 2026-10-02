package io.legado.app.ui.book.read

import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyles
import io.legado.app.ui.font.FontSelectDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighlightStyleDialogTest {

    private class Host : HighlightStyleDialog.StyleHost {
        override fun currentHighlightStyle(): HighlightStyle = HighlightStyle()

        override fun onHighlightStyleChanged(style: HighlightStyle) = Unit

        override fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean) = Unit
    }

    @Test
    fun resolvesParentHostBeforeActivityHost() {
        val parentHost = Host()

        assertSame(parentHost, HighlightStyleDialog.resolveStyleHost(parentHost, Host()))
    }

    @Test
    fun fallsBackToActivityHost() {
        val activityHost = Host()

        assertSame(activityHost, HighlightStyleDialog.resolveStyleHost(null, activityHost))
    }

    @Test
    fun returnsNullWithoutAHost() {
        assertNull(HighlightStyleDialog.resolveStyleHost(Any(), Any()))
    }

    @Test
    fun emptyOrMissingStyleFallsBackToAVisiblePreset() {
        assertEquals(HighlightStyles.presets.first(), visibleHighlightStyle(null))
        assertEquals(HighlightStyles.presets.first(), visibleHighlightStyle(HighlightStyle()))
    }

    @Test
    fun configuredStyleIsKept() {
        val style = HighlightStyle(textColor = 1)

        assertSame(style, visibleHighlightStyle(style))
    }

    @Test
    fun fillPresetsHaveDistinctSwatches() {
        val colors = HighlightStyles.presets.mapNotNull { style ->
            style.fill.takeIf { it != 0 }
        }

        assertEquals(colors.size, colors.distinct().size)
    }

    @Test
    fun shadowEditorOpensOnlyForTheFirstEnableTransition() {
        val disabled = HighlightStyle()
        val enabled = HighlightStyle(shadow = HighlightStyle.Shadow())

        assertTrue(HighlightStyleDialog.shouldOpenShadowEditor(disabled, enabled))
        assertFalse(HighlightStyleDialog.shouldOpenShadowEditor(enabled, enabled))
        assertFalse(HighlightStyleDialog.shouldOpenShadowEditor(disabled, disabled))
    }

    @Test
    fun underlineControlsUseHalfDpStepsAcrossFullRange() {
        assertEquals(0, halfStepProgress(0f, 0f, 10f))
        assertEquals(1, halfStepProgress(.5f, 0f, 10f))
        assertEquals(20, halfStepProgress(99f, 0f, 10f))
        assertEquals(60, halfStepProgress(30f, 0f, 30f))
        assertEquals(0, halfStepProgress(Float.NaN, 0f, 10f))
        assertEquals(0, halfStepProgress(Float.POSITIVE_INFINITY, 0f, 10f))
    }

    @Test
    fun shadowOffsetsRepresentNegativeAndPositiveHalfDpSteps() {
        assertEquals(0, halfStepProgress(-99f, -10f, 10f))
        assertEquals(19, halfStepProgress(-.5f, -10f, 10f))
        assertEquals(20, halfStepProgress(0f, -10f, 10f))
        assertEquals(21, halfStepProgress(.5f, -10f, 10f))
        assertEquals(40, halfStepProgress(99f, -10f, 10f))
    }

    @Test
    fun highlightDefaultFontDoesNotChangeTheGlobalSystemTypeface() {
        fun callback(selectSystemTypeface: Boolean) = object : FontSelectDialog.CallBack {
            override val curFontPath = ""
            override val selectSystemTypefaceOnDefault = selectSystemTypeface
            override fun selectFont(path: String) = Unit
        }

        assertTrue(FontSelectDialog.shouldSelectSystemTypeface(callback(true)))
        assertFalse(FontSelectDialog.shouldSelectSystemTypeface(callback(false)))
    }

    @Test
    fun fontSelectionReturnsOnTheUiThread() {
        val source = sequenceOf(
            File("src/main/java/io/legado/app/ui/font/FontSelectDialog.kt"),
            File("app/src/main/java/io/legado/app/ui/font/FontSelectDialog.kt")
        ).first(File::isFile).readText()
        val callback = source.substringAfter("override fun onFontSelect")
            .substringBefore("private fun onDefaultFontChange")
        val highlightDialog = sequenceOf(
            File("src/main/java/io/legado/app/ui/book/read/HighlightStyleDialog.kt"),
            File("app/src/main/java/io/legado/app/ui/book/read/HighlightStyleDialog.kt")
        ).first(File::isFile).readText()

        assertTrue(callback.contains("callBack?.selectFont"))
        assertFalse(callback.contains("execute"))
        assertTrue(highlightDialog.contains("invalidateHighlightTypeface(path)"))
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $pathInApp")
}
