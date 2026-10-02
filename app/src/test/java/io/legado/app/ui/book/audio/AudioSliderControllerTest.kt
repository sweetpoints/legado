package io.legado.app.ui.book.audio

import org.junit.Assert.*
import org.junit.Test

class AudioSliderControllerTest {
    @Test fun timerKeepsWholeMinuteBoundsAndUserChangesDispatchOnce() {
        val changed = mutableListOf<Float>(); val model = AudioSliderController(AudioSliderMode.Timer) { changed += it }
        model.user(12.9f); model.user(13.2f); model.user(500f); model.user(-1f)
        assertEquals(listOf(13f, 180f, 0f), changed); assertEquals(179, model.state.value.steps)
    }
    @Test fun speedKeepsTenthsAndPlaybackBounds() {
        val changed = mutableListOf<Float>(); val model = AudioSliderController(AudioSliderMode.Speed) { changed += it }
        model.user(1.76f); model.user(0f); model.user(5f)
        assertEquals(listOf(1.8f, .5f, 3f), changed); assertEquals(24, model.state.value.steps)
        assertEquals(.5f..3f, model.state.value.range)
    }
    @Test fun programmaticSnapshotDoesNotChangePlaybackAndNonFiniteInputIsIgnored() {
        val changed = mutableListOf<Float>(); val model = AudioSliderController(AudioSliderMode.Speed) { changed += it }
        model.refresh(2.3f); model.user(Float.NaN); model.refresh(Float.POSITIVE_INFINITY)
        assertEquals(2.3f, model.state.value.value); assertTrue(changed.isEmpty())
        model.user(2.3f); assertTrue(changed.isEmpty())
    }
}
