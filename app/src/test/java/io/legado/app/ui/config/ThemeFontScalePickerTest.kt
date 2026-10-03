package io.legado.app.ui.config

import io.legado.app.model.theme.ThemeSettingsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeFontScalePickerTest {
    @Test
    fun fontScalePickerKeepsConfiguredOrEffectiveSystemValueBeforeClamping() {
        assertEquals(13, ThemeSettingsSnapshot(fontScale = 13, systemFontScale = 1.1f).fontPicker)
        assertEquals(12, ThemeSettingsSnapshot(fontScale = 0, systemFontScale = 1.16f).fontPicker)
        assertEquals(9, ThemeSettingsSnapshot(fontScale = 99, systemFontScale = .91f).fontPicker)
        assertEquals(8, ThemeSettingsSnapshot(fontScale = -1, systemFontScale = .7f).fontPicker)
        assertEquals(16, ThemeSettingsSnapshot(fontScale = 0, systemFontScale = 2f).fontPicker)
    }
}
