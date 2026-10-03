package io.legado.app.data.repository

import io.legado.app.help.HighlightStyle
import org.junit.Assert.*
import org.junit.Test

class HighlightStyleRepositoryTest {
    private val repo = HighlightStyleRepository()

    @Test
    fun everyChannelEnablesIndependentlyAndDisablingPreservesOtherStyleMetadata() {
        HighlightChannel.entries.forEach { channel ->
            val base =
                HighlightStyle(
                    fontPath = "font",
                    fontSize = 17f,
                    letterSpacing = .2f,
                    pillPaddingScale = .8f,
                )
            val enabled = repo.toggle(base, channel, true)
            assertTrue(repo.enabled(enabled, channel))
            assertFalse(repo.enabled(base, channel))
            assertEquals(base, repo.toggle(enabled, channel, false))
        }
    }

    @Test
    fun repeatedEnableKeepsConfiguredDecorationAndDefaultColorsAreVisible() {
        val base =
            HighlightStyle(
                underline = HighlightStyle.Underline(HighlightStyle.Kind.DOUBLE, 7, 4f, 3f),
                shadow = HighlightStyle.Shadow(6f, -2f, 4f, 8),
                fill = 9,
                textColor = 10,
            )
        listOf(
                HighlightChannel.Fill,
                HighlightChannel.Text,
                HighlightChannel.Underline,
                HighlightChannel.Shadow,
            )
            .forEach {
                assertEquals(base, repo.toggle(base, it, true))
            }
        assertEquals(
            HighlightStyleRepository.DEFAULT_FILL,
            repo.toggle(HighlightStyle(), HighlightChannel.Fill, true).fill,
        )
        assertEquals(
            HighlightStyleRepository.DEFAULT_TEXT,
            repo.toggle(HighlightStyle(), HighlightChannel.Text, true).textColor,
        )
    }

    @Test
    fun changingColorsRetainsUnderlineAndShadowDetailsAndOtherFields() {
        val base =
            HighlightStyle(
                bold = true,
                underline = HighlightStyle.Underline(HighlightStyle.Kind.DOTTED, 1, 2f, 3f),
                shadow = HighlightStyle.Shadow(4f, 5f, 6f, 2),
                fontPath = "font",
            )
        assertEquals(
            base.copy(underline = base.underline!!.copy(color = 99)),
            repo.applyColor(base, HighlightChannel.Underline, 99),
        )
        assertEquals(
            base.copy(shadow = base.shadow!!.copy(color = 99)),
            repo.applyColor(base, HighlightChannel.Shadow, 99),
        )
        assertEquals(base, repo.applyColor(base, HighlightChannel.Bold, 99))
    }

    @Test
    fun fillAndUnderlineCycleAllOptionsAndWrapWithoutLosingTheirMetrics() {
        var fill = HighlightStyle(fill = 1, pillPaddingScale = .75f)
        val shapes = mutableListOf(fill.resolvedFillShape)
        repeat(HighlightStyle.FillShape.entries.size) {
            fill = repo.cycle(fill, HighlightChannel.Fill)
            shapes += fill.resolvedFillShape
        }
        assertEquals(HighlightStyle.FillShape.entries, shapes.dropLast(1))
        assertEquals(shapes.first(), shapes.last())
        assertEquals(.75f, fill.pillPaddingScale)
        var underline =
            HighlightStyle(
                underline = HighlightStyle.Underline(color = 7, width = 3f, distance = 8f)
            )
        val kinds = mutableListOf(underline.underline!!.kind)
        repeat(HighlightStyle.Kind.entries.size) {
            underline = repo.cycle(underline, HighlightChannel.Underline)
            kinds += underline.underline!!.kind
        }
        assertEquals(HighlightStyle.Kind.entries, kinds.dropLast(1))
        assertEquals(kinds.first(), kinds.last())
        assertEquals(
            HighlightStyle.Underline(color = 7, width = 3f, distance = 8f),
            underline.underline,
        )
    }

    @Test
    fun numberSettingsKeepOriginalRangesAndNullMeansInherit() {
        val base = HighlightStyle(fontPath = "font", bold = true)
        assertEquals(5f, repo.fontSize(base, 0).fontSize)
        assertEquals(100f, repo.fontSize(base, 200).fontSize)
        assertEquals(-.5f, repo.letterSpacing(base, -99).letterSpacing)
        assertEquals(1f, repo.letterSpacing(base, 200).letterSpacing)
        assertEquals(.25f, repo.pillPadding(base, 0).pillPaddingScale)
        assertEquals(2f, repo.pillPadding(base, 999).pillPaddingScale)
        assertEquals(base, repo.fontSize(repo.fontSize(base, 30), null))
        assertEquals(base, repo.letterSpacing(repo.letterSpacing(base, 30), null))
        assertEquals(base, repo.pillPadding(repo.pillPadding(base, 30), null))
    }

    @Test
    fun presetSwatchFollowsExistingChannelPriorityAndFallback() {
        assertEquals(HighlightStyleRepository.DEFAULT_SWATCH, repo.swatch(HighlightStyle()))
        assertEquals(
            1,
            repo.swatch(
                HighlightStyle(fill = 1, textColor = 2, shadow = HighlightStyle.Shadow(color = 3))
            ),
        )
        assertEquals(
            2,
            repo.swatch(HighlightStyle(textColor = 2, shadow = HighlightStyle.Shadow(color = 3))),
        )
        assertEquals(3, repo.swatch(HighlightStyle(shadow = HighlightStyle.Shadow(color = 3))))
        assertTrue(repo.presets.isNotEmpty())
        assertTrue(repo.presets.all { repo.swatch(it) != 0 })
    }
}
