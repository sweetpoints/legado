package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.TipSetting

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TitleNumberSpacingTest {

    @Test
    fun `spacing maps to seek bar progress`() {
        assertEquals(0, titleNumberSpacingToProgress(-50))
        assertEquals(50, titleNumberSpacingToProgress(0))
        assertEquals(150, titleNumberSpacingToProgress(100))
    }

    @Test
    fun `seek bar progress maps to spacing`() {
        assertEquals(-50, titleNumberSpacingFromProgress(0))
        assertEquals(0, titleNumberSpacingFromProgress(50))
        assertEquals(100, titleNumberSpacingFromProgress(150))
    }

    @Test
    fun `out of range values are clamped`() {
        assertEquals(0, titleNumberSpacingToProgress(-51))
        assertEquals(150, titleNumberSpacingToProgress(101))
        assertEquals(-50, titleNumberSpacingFromProgress(-1))
        assertEquals(100, titleNumberSpacingFromProgress(151))
    }

    @Test
    fun `number spacing remains signed while stored and clamped`() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        model.set(TipSetting.TitleNumberSpacing, -51)
        assertEquals(-50, model.state.value.settings[TipSetting.TitleNumberSpacing])
        model.set(TipSetting.TitleNumberSpacing, 0)
        assertEquals(0, model.state.value.settings[TipSetting.TitleNumberSpacing])
        model.set(TipSetting.TitleNumberSpacing, 101)
        assertEquals(100, model.state.value.settings[TipSetting.TitleNumberSpacing])
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .first { it.isFile }
    }
}
