package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.HighlightChannel
import io.legado.app.help.HighlightStyle
import org.junit.Assert.*
import org.junit.Test

class HighlightStyleViewModelTest {
    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun enablingShadowQueuesLiveApplyBeforeOpeningEditorOnlyOnFirstTransition() {
        val model = HighlightStyleViewModel(SavedStateHandle())
        model.attach(HighlightStyle())
        model.toggle(HighlightChannel.Shadow, true)
        assertEquals(
            listOf(HighlightStyleAction.Apply, HighlightStyleAction.Shadow),
            model.state.value.effects.map { it.action },
        )
        val shadow = model.state.value.style.shadow
        model.state.value.effects.toList().forEach { model.consume(it.id) }
        model.toggle(HighlightChannel.Shadow, true)
        assertEquals(
            listOf(HighlightStyleAction.Apply),
            model.state.value.effects.map { it.action },
        )
        assertEquals(shadow, model.state.value.style.shadow)
    }

    @Test
    fun pendingLiveApplySurvivesSavedStateAndAttachCannotOverwriteItWithOldHost() {
        val saved = SavedStateHandle()
        val model = HighlightStyleViewModel(saved)
        model.attach(HighlightStyle())
        model.toggle(HighlightChannel.Bold, true)
        val restored = HighlightStyleViewModel(copy(saved))
        restored.attach(HighlightStyle())
        assertTrue(restored.state.value.style.bold)
        assertEquals(1, restored.state.value.effects.size)
        restored.consume(restored.state.value.effects.single().id)
        restored.acceptHost(HighlightStyle(bold = true))
        val again = HighlightStyleViewModel(copy(saved))
        assertEquals(
            1,
            again.state.value.effects.size,
        ) // original snapshot still has the unconsumed original action
    }

    @Test
    fun consumedActionsDoNotReplayAfterRestoreAndHostNormalizationUpdatesVisibleStyle() {
        val saved = SavedStateHandle()
        val model = HighlightStyleViewModel(saved)
        model.attach(HighlightStyle(fill = 1))
        model.toggle(HighlightChannel.Fill, false)
        model.consume(model.state.value.effects.single().id)
        model.acceptHost(HighlightStyle(fill = 2))
        val restored = HighlightStyleViewModel(copy(saved))
        assertEquals(2, restored.state.value.style.fill)
        assertTrue(restored.state.value.effects.isEmpty())
    }

    @Test
    fun externalColorRefreshIsAuthoritativeAndDoesNotQueueAnotherHostWrite() {
        val model = HighlightStyleViewModel(SavedStateHandle())
        model.attach(HighlightStyle())
        model.toggle(HighlightChannel.Bold, true)
        model.refresh(HighlightStyle(fill = 99))
        assertEquals(HighlightStyle(fill = 99), model.state.value.style)
        assertTrue(model.state.value.effects.isEmpty())
        model.refresh(model.state.value.style)
        assertTrue(model.state.value.effects.isEmpty())
    }

    @Test
    fun latestQueuedEditIsNotOverwrittenByEarlierHostAcknowledgement() {
        val model = HighlightStyleViewModel(SavedStateHandle())
        model.attach(HighlightStyle())
        model.toggle(HighlightChannel.Bold, true)
        model.toggle(HighlightChannel.Italic, true)
        val first = model.state.value.effects.first()
        model.consume(first.id)
        model.acceptHost(first.style)
        assertTrue(model.state.value.style.bold)
        assertTrue(model.state.value.style.italic)
    }

    @Test
    fun emptyAndNegativeNumberDraftsRestoreWithoutApplyingAndCancelKeepsStyle() {
        val saved = SavedStateHandle()
        val model = HighlightStyleViewModel(saved)
        model.attach(HighlightStyle(letterSpacing = .25f))
        model.number(HighlightNumber.LetterSpacing, 0)
        model.numberText("-")
        val restored = HighlightStyleViewModel(copy(saved))
        assertEquals("-", restored.state.value.number!!.text)
        restored.saveNumber()
        assertTrue(restored.state.value.effects.isEmpty())
        assertNotNull(restored.state.value.number)
        restored.numberText("")
        assertEquals("", restored.state.value.number!!.text)
        restored.dismissNumber()
        assertEquals(.25f, restored.state.value.style.letterSpacing)
        assertTrue(restored.state.value.effects.isEmpty())
    }

    @Test
    fun numericInputClampsEvenAtSameBoundaryAndRejectsOverflowWithoutChangingDraft() {
        val model = HighlightStyleViewModel(SavedStateHandle())
        model.attach(HighlightStyle(fontSize = 100f))
        model.number(HighlightNumber.FontSize, 20)
        model.numberText("999")
        assertEquals("100", model.state.value.number!!.text)
        model.numberText("999999999999999999999")
        assertEquals("100", model.state.value.number!!.text)
        model.numberValue(3)
        assertEquals("5", model.state.value.number!!.text)
        model.saveNumber()
        assertEquals(5f, model.state.value.style.fontSize)
        assertNull(model.state.value.number)
        model.number(HighlightNumber.FontSize, 20)
        model.saveNumber(true)
        assertNull(model.state.value.style.fontSize)
    }

    @Test
    fun fontAndChildDecorationEditsKeepUnrelatedStyleAndMarkFontInvalidationOnlyForFont() {
        val model = HighlightStyleViewModel(SavedStateHandle())
        model.attach(HighlightStyle(bold = true, fill = 7))
        model.selectFont("font")
        assertTrue(model.state.value.effects.last().fontChanged)
        val shadow = HighlightStyle.Shadow(6f, -3f, 4f, 9)
        model.shadow(shadow)
        val underline = HighlightStyle.Underline(HighlightStyle.Kind.DOUBLE, 8, 5f, 6f)
        model.underline(underline)
        assertEquals(
            HighlightStyle(
                fill = 7,
                bold = true,
                fontPath = "font",
                shadow = shadow,
                underline = underline,
            ),
            model.state.value.style,
        )
        assertTrue(model.state.value.effects.drop(1).none { it.fontChanged })
        model.selectFont("")
        assertEquals("", model.state.value.style.fontPath)
    }

    @Test
    fun extraAndTuneRespectEnabledChannelAndPillPaddingVisibility() {
        val model = HighlightStyleViewModel(SavedStateHandle())
        model.attach(HighlightStyle())
        model.extra(HighlightChannel.Fill)
        model.tune(HighlightChannel.Underline)
        model.color(HighlightChannel.Fill)
        assertTrue(model.state.value.effects.isEmpty())
        model.attach(
            HighlightStyle(
                fill = 1,
                fillShape = HighlightStyle.FillShape.PILL,
                pillPaddingScale = .55f,
            )
        )
        model.tune(HighlightChannel.Fill)
        assertEquals(55, model.state.value.number!!.value)
        model.saveNumber(true)
        assertNull(model.state.value.style.pillPaddingScale)
        model.extra(HighlightChannel.Fill)
        assertEquals(HighlightStyle.FillShape.RECTANGLE, model.state.value.style.resolvedFillShape)
    }
}
