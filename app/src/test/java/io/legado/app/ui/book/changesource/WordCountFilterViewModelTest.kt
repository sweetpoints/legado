package io.legado.app.ui.book.changesource

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.*
import org.junit.Assert.*
import org.junit.Test

class WordCountFilterViewModelTest {
    @Test
    fun selectingModeUsesLegacyDefaultsAndKeepsSameModeStoredRange() {
        val repo = Repository()
        val model = WordCountFilterViewModel(repo, SavedStateHandle())
        model.choose(1)
        assertEquals("1000", model.state.value.minimum)
        assertEquals("5000", model.state.value.maximum)
        model.choose(2)
        assertEquals("70", model.state.value.minimum)
        assertEquals("130", model.state.value.maximum)
        repo.current = WordCountFilterSettings(2, 60, 120)
        model.choose(2)
        assertEquals("60", model.state.value.minimum)
        assertEquals("120", model.state.value.maximum)
        assertTrue(repo.writes.isEmpty())
    }

    @Test
    fun disabledModePreservesBoundsAndRefreshesWithoutReloadingMeasurementsOnce() {
        val repo = Repository(WordCountFilterSettings(2, 70, 130))
        val model = WordCountFilterViewModel(repo, SavedStateHandle())
        model.choose(0)
        model.choose(0)
        assertEquals(listOf(WordCountFilterSettings(0, 70, 130)), repo.writes)
        assertEquals(false, model.consumeReload())
        assertNull(model.consumeReload())
        assertTrue(model.state.value.finished)
    }

    @Test
    fun unchangedDisabledOrRangeSettingsDoNotWriteOrNotify() {
        val repo = Repository()
        val model = WordCountFilterViewModel(repo, SavedStateHandle())
        model.choose(0)
        assertTrue(repo.writes.isEmpty())
        assertNull(model.consumeReload())
        repo.current = WordCountFilterSettings(1, 1000, 5000)
        val next = WordCountFilterViewModel(repo, SavedStateHandle())
        next.choose(1)
        next.confirm()
        assertTrue(repo.writes.isEmpty())
        assertNull(next.consumeReload())
        assertTrue(next.state.value.finished)
    }

    @Test
    fun invalidNegativeReversedEmptyAndOverflowRangesStayOpenWithoutWrites() {
        val repo = Repository()
        val model = WordCountFilterViewModel(repo, SavedStateHandle())
        model.choose(1)
        listOf("-1" to "2", "10" to "9", "" to "1", "2147483648" to "2147483648", "1x" to "5")
            .forEach { (min, max) ->
                model.minimum(min)
                model.maximum(max)
                model.confirm()
                assertTrue(model.state.value.invalid)
                assertFalse(model.state.value.finished)
                assertTrue(repo.writes.isEmpty())
            }
    }

    @Test
    fun equalZeroBoundsAreValidAndChangedRangesRequestMeasurementsOnce() {
        val repo = Repository()
        val model = WordCountFilterViewModel(repo, SavedStateHandle())
        model.choose(1)
        model.minimum("0")
        model.maximum("0")
        model.confirm()
        model.confirm()
        assertEquals(listOf(WordCountFilterSettings(1, 0, 0)), repo.writes)
        assertEquals(true, model.consumeReload())
        assertNull(model.consumeReload())
    }

    @Test
    fun modeAndDraftRestoreWithoutReadingDefaultsOverThemOrWritingPreferences() {
        val repo = Repository()
        val saved = SavedStateHandle()
        val model = WordCountFilterViewModel(repo, saved)
        model.choose(2)
        model.minimum("80")
        model.maximum("150")
        repo.current = WordCountFilterSettings(1, 1, 2)
        val restored = WordCountFilterViewModel(repo, snapshot(saved))
        assertEquals(2, restored.state.value.mode)
        assertEquals("80", restored.state.value.minimum)
        assertEquals("150", restored.state.value.maximum)
        assertTrue(repo.writes.isEmpty())
        restored.close()
        restored.confirm()
        assertTrue(repo.writes.isEmpty())
    }

    @Test
    fun restoredPendingReloadIsConsumedOnceAndFinishedStateCannotWriteAgain() {
        val repo = Repository()
        val saved = SavedStateHandle()
        val model = WordCountFilterViewModel(repo, saved)
        model.choose(2)
        model.confirm()
        val restoredSaved = snapshot(saved)
        val restored = WordCountFilterViewModel(repo, restoredSaved)
        assertEquals(true, restored.consumeReload())
        restored.choose(1)
        restored.minimum("99")
        restored.confirm()
        assertEquals(1, repo.writes.size)
        val next = WordCountFilterViewModel(repo, snapshot(restoredSaved))
        assertNull(next.consumeReload())
        assertTrue(next.state.value.finished)
    }

    @Test
    fun failedPreferenceSaveRetainsEditableDraftAndRetryCanComplete() {
        val repo = Repository().apply { fail = true }
        val model = WordCountFilterViewModel(repo, SavedStateHandle())
        model.choose(1)
        model.confirm()
        assertFalse(model.state.value.finished)
        assertNotNull(model.state.value.error)
        assertNull(model.consumeReload())
        model.minimum("2000")
        assertNull(model.state.value.error)
        repo.fail = false
        model.confirm()
        assertEquals(WordCountFilterSettings(1, 2000, 5000), repo.current)
        assertEquals(true, model.consumeReload())
    }

    private fun snapshot(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private class Repository(var current: WordCountFilterSettings = WordCountFilterSettings()) :
        WordCountFilterRepository {
        val writes = mutableListOf<WordCountFilterSettings>()
        var fail = false

        override fun load() = current

        override fun save(settings: WordCountFilterSettings) {
            if (fail) error("write failed")
            writes += settings
            current = settings
        }
    }
}
