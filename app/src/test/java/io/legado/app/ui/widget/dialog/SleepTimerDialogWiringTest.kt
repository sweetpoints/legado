package io.legado.app.ui.widget.dialog

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.SleepTimerMode
import io.legado.app.data.preferences.SleepTimerPreferences
import io.legado.app.service.MAX_CHAPTER_STOP_COUNT
import io.legado.app.ui.widget.dialog.sleeptimer.*
import org.junit.Assert.*
import org.junit.Test

/** Timer contracts exercise the real ViewModel rather than inspecting UI source or XML. */
class SleepTimerDialogWiringTest {
    @Test
    fun argumentsBoundCurrentTimersAndRetainEpisodeMode() {
        val model =
            SleepTimerViewModel(
                FakePreferences(),
                SavedStateHandle(
                    mapOf("minute" to Int.MAX_VALUE, "chapter" to Int.MAX_VALUE, "episodes" to true)
                ),
            )
        assertEquals(180, model.state.value.minute)
        assertEquals(MAX_CHAPTER_STOP_COUNT, model.state.value.chapter)
        assertTrue(model.state.value.useEpisodes)
        val negative =
            SleepTimerViewModel(
                FakePreferences(),
                SavedStateHandle(mapOf("minute" to -1, "chapter" to -1)),
            )
        assertFalse(negative.state.value.isActive)
    }

    @Test
    fun everyMinutePresetSelectsExactlyItsMinutesWithoutChangingCustomHistory() {
        for (value in listOf(15, 30, 45, 60)) {
            val preferences = FakePreferences()
            val model = SleepTimerViewModel(preferences, SavedStateHandle())
            model.selectPreset(SleepTimerMode.Minutes, value)
            assertEquals(
                SleepTimerSelection(SleepTimerMode.Minutes, value),
                model.consumeSelection(),
            )
            assertEquals(listOf(false), preferences.prefer)
            assertTrue(preferences.remembered.isEmpty())
        }
    }

    @Test
    fun everyChapterPresetSelectsExactlyItsChaptersAndRemembersMode() {
        for (value in listOf(1, 2, 3, 5)) {
            val preferences = FakePreferences()
            val model = SleepTimerViewModel(preferences, SavedStateHandle())
            model.selectPreset(SleepTimerMode.Chapters, value)
            assertEquals(
                SleepTimerSelection(SleepTimerMode.Chapters, value),
                model.consumeSelection(),
            )
            assertEquals(listOf(true), preferences.prefer)
            assertTrue(preferences.remembered.isEmpty())
        }
    }

    @Test
    fun customHistoryWinsOverCurrentValueAndReopeningDoesNotReplaceInput() {
        val preferences =
            FakePreferences().apply {
                minutes = 90
                chapters = 7
            }
        val model =
            SleepTimerViewModel(
                preferences,
                SavedStateHandle(mapOf("minute" to 30, "chapter" to 2)),
            )
        model.showCustom(SleepTimerMode.Minutes)
        assertEquals("90", model.state.value.input)
        model.setInput("123")
        model.showCustom(SleepTimerMode.Minutes)
        assertEquals("123", model.state.value.input)
        model.showCustom(SleepTimerMode.Chapters)
        assertEquals("7", model.state.value.input)
    }

    @Test
    fun absentHistoryFallsBackToCurrentActiveTimerOrBlank() {
        val model = SleepTimerViewModel(FakePreferences(), SavedStateHandle(mapOf("minute" to 30)))
        model.showCustom(SleepTimerMode.Minutes)
        assertEquals("30", model.state.value.input)
        model.showCustom(SleepTimerMode.Chapters)
        assertEquals("", model.state.value.input)
    }

    @Test
    fun minuteAndChapterUpperBoundsAreAcceptedAndWrittenOnlyForCustomSelection() {
        for ((mode, value) in
            listOf(
                SleepTimerMode.Minutes to 180,
                SleepTimerMode.Chapters to MAX_CHAPTER_STOP_COUNT,
            )) {
            val preferences = FakePreferences()
            val model = SleepTimerViewModel(preferences, SavedStateHandle())
            model.showCustom(mode)
            model.setInput(value.toString())
            model.confirmCustom()
            assertEquals(SleepTimerSelection(mode, value), model.consumeSelection())
            assertEquals(listOf(mode to value), preferences.remembered)
        }
    }

    @Test
    fun invalidCustomInputsDoNotDeliverSaveOrSwitchPreference() {
        for ((mode, values) in
            listOf(
                SleepTimerMode.Minutes to listOf("", "0", "181", "999"),
                SleepTimerMode.Chapters to listOf("", "0", "100"),
            )) {
            for (value in values) {
                val preferences = FakePreferences()
                val model = SleepTimerViewModel(preferences, SavedStateHandle())
                model.showCustom(mode)
                model.setInput(value)
                model.confirmCustom()
                assertTrue(model.state.value.showValidation)
                assertNull(model.consumeSelection())
                assertTrue(preferences.prefer.isEmpty())
                assertTrue(preferences.remembered.isEmpty())
            }
        }
    }

    @Test
    fun inputRejectsNonNumericAndOverlongValuesAndEditingClearsError() {
        val model = SleepTimerViewModel(FakePreferences(), SavedStateHandle())
        model.showCustom(SleepTimerMode.Minutes)
        model.setInput("12")
        for (invalid in listOf("-1", "abc", "1234", " 3")) model.setInput(invalid)
        assertEquals("12", model.state.value.input)
        model.setInput("0")
        model.confirmCustom()
        model.setInput("13")
        assertFalse(model.state.value.showValidation)
    }

    @Test
    fun customDraftAndModeSurviveRecreationWithoutSavingPreferences() {
        val preferences = FakePreferences()
        val handle = SavedStateHandle(mapOf("episodes" to true))
        val model = SleepTimerViewModel(preferences, handle)
        model.showCustom(SleepTimerMode.Chapters)
        model.setInput("9")
        val restored = SleepTimerViewModel(preferences, copy(handle))
        assertEquals(SleepTimerMode.Chapters, restored.state.value.customMode)
        assertEquals("9", restored.state.value.input)
        assertTrue(restored.state.value.useEpisodes)
        assertTrue(preferences.remembered.isEmpty())
        assertTrue(preferences.prefer.isEmpty())
    }

    @Test
    fun pendingDeliverySurvivesRecreationAndIsConsumedExactlyOnce() {
        val preferences = FakePreferences()
        val handle = SavedStateHandle()
        val model = SleepTimerViewModel(preferences, handle)
        model.selectPreset(SleepTimerMode.Chapters, 3)
        model.selectPreset(SleepTimerMode.Minutes, 30)
        val restoredHandle = copy(handle)
        val restored = SleepTimerViewModel(preferences, restoredHandle)
        assertEquals(SleepTimerSelection(SleepTimerMode.Chapters, 3), restored.consumeSelection())
        assertNull(restored.consumeSelection())
        restored.selectPreset(SleepTimerMode.Minutes, 30)
        val afterDelivery = SleepTimerViewModel(preferences, copy(restoredHandle))
        assertNull(afterDelivery.consumeSelection())
        assertTrue(afterDelivery.state.value.finished)
        assertEquals(listOf(true), preferences.prefer)
    }

    @Test
    fun consumedSelectionRestoresFinishedStateWithoutPendingCallback() {
        val preferences = FakePreferences()
        val handle = SavedStateHandle()
        val model = SleepTimerViewModel(preferences, handle)
        model.selectPreset(SleepTimerMode.Minutes, 30)
        model.consumeSelection()
        val restored = SleepTimerViewModel(preferences, copy(handle))
        assertTrue(restored.state.value.finished)
        assertNull(restored.state.value.pending)
        assertNull(restored.consumeSelection())
        assertEquals(listOf(false), preferences.prefer)
    }

    @Test
    fun turningOffSendsZeroMinutesWithoutChangingPreferredMode() {
        val preferences = FakePreferences()
        val model = SleepTimerViewModel(preferences, SavedStateHandle(mapOf("chapter" to 3)))
        model.turnOff()
        assertEquals(SleepTimerSelection(SleepTimerMode.Minutes, 0), model.consumeSelection())
        assertTrue(preferences.prefer.isEmpty())
        val inactive = SleepTimerViewModel(preferences, SavedStateHandle())
        inactive.turnOff()
        assertNull(inactive.consumeSelection())
    }

    @Test
    fun unknownPresetsAndTypingAloneDoNotCommit() {
        val preferences = FakePreferences()
        val model = SleepTimerViewModel(preferences, SavedStateHandle())
        model.selectPreset(SleepTimerMode.Minutes, 20)
        model.selectPreset(SleepTimerMode.Chapters, 4)
        model.showCustom(SleepTimerMode.Minutes)
        model.setInput("12")
        assertNull(model.consumeSelection())
        assertTrue(preferences.remembered.isEmpty())
        assertTrue(preferences.prefer.isEmpty())
    }

    private fun copy(handle: SavedStateHandle) =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })

    private class FakePreferences : SleepTimerPreferences {
        var minutes = 0
        var chapters = 0
        val prefer = mutableListOf<Boolean>()
        val remembered = mutableListOf<Pair<SleepTimerMode, Int>>()

        override fun lastCustom(mode: SleepTimerMode) =
            if (mode == SleepTimerMode.Minutes) minutes else chapters

        override fun rememberCustom(mode: SleepTimerMode, value: Int) {
            remembered += mode to value
        }

        override fun preferChapters(chapters: Boolean) {
            prefer += chapters
        }
    }
}
