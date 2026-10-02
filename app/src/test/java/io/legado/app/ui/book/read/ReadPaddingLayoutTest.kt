package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.PaddingRegion
import io.legado.app.data.preferences.PaddingSide
import io.legado.app.ui.book.read.config.FakePaddingSettingsRepository
import io.legado.app.ui.book.read.config.PaddingSettingsViewModel
import org.junit.Assert.*
import org.junit.Test

class ReadPaddingLayoutTest {
    @Test fun allFourControlsUpdateTheirMatchingSides() {
        val repository = FakePaddingSettingsRepository()
        val model = PaddingSettingsViewModel(repository, SavedStateHandle())
        model.setLock(false)
        PaddingSide.entries.forEach { side ->
            val before = model.state.value.current[side]
            model.step(side, 1)
            assertEquals(before + 1, repository.snapshot[PaddingRegion.BODY][side])
            assertEquals(side, repository.edits.last().side)
        }
    }
    @Test fun verticalSidesSupport400AndHorizontalSidesStopAt100() {
        val model = PaddingSettingsViewModel(FakePaddingSettingsRepository(), SavedStateHandle())
        model.setLock(false)
        PaddingSide.entries.forEach {
            model.step(it, 1000)
            assertEquals(if (it in listOf(PaddingSide.TOP, PaddingSide.BOTTOM)) 400 else 100, model.state.value.current[it])
            model.step(it, -1000)
            assertEquals(0, model.state.value.current[it])
        }
    }
    @Test fun regionSelectionKeepsEachAreasIndependentValuesAndRecomputesLock() {
        val model = PaddingSettingsViewModel(FakePaddingSettingsRepository(), SavedStateHandle())
        model.step(PaddingSide.TOP, 10)
        model.selectRegion(PaddingRegion.HEADER)
        assertEquals(0, model.state.value.current.top)
        assertTrue(model.state.value.lockLR)
        model.selectRegion(PaddingRegion.FOOTER)
        assertFalse(model.state.value.lockLR)
        model.selectRegion(PaddingRegion.BODY)
        assertEquals(16, model.state.value.current.top)
    }
    @Test fun lineSwitchDoesNotAlterUnselectedRegion() {
        val repository = FakePaddingSettingsRepository()
        val model = PaddingSettingsViewModel(repository, SavedStateHandle())
        model.selectRegion(PaddingRegion.HEADER)
        model.setShowLine(true)
        assertTrue(repository.snapshot[PaddingRegion.HEADER].showLine)
        assertFalse(repository.snapshot[PaddingRegion.FOOTER].showLine)
        model.selectRegion(PaddingRegion.FOOTER)
        model.setShowLine(true)
        assertTrue(repository.snapshot[PaddingRegion.FOOTER].showLine)
    }
    @Test fun resetCancelKeepsTheSelectedRegionsSettings() {
        val repository = FakePaddingSettingsRepository()
        val model = PaddingSettingsViewModel(repository, SavedStateHandle())
        val initial = model.state.value.snapshot
        model.askReset()
        model.cancelReset()
        model.confirmReset()
        assertEquals(initial, repository.snapshot)
        assertTrue(repository.resets.isEmpty())
    }
}
