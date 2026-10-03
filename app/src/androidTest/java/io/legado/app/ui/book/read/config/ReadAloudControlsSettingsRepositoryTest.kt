package io.legado.app.ui.book.read.config

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.preferences.PreferenceReadAloudControlsSettingsRepository
import io.legado.app.data.preferences.ReadAloudControlsNumber
import io.legado.app.data.preferences.ReadAloudControlsToggle
import io.legado.app.utils.defaultSharedPreferences
import org.junit.Assert.*
import org.junit.Test

class ReadAloudControlsSettingsRepositoryTest {
    @Test
    fun legacyKeysDefaultsAndNumericBoundsRemainCompatible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.defaultSharedPreferences
        val keys =
            ReadAloudControlsToggle.entries.map { it.key } +
                ReadAloudControlsNumber.entries.map { it.key } +
                PreferKey.readAloudControlsSize
        val previous = keys.associateWith { preferences.all[it] }
        try {
            preferences
                .edit()
                .apply { keys.forEach { remove(it) } }
                .putInt(PreferKey.readAloudControlsSize, 72)
                .putBoolean(PreferKey.readAloudControlsPause, false)
                .commit()
            val repository = PreferenceReadAloudControlsSettingsRepository(context)
            assertEquals(432, repository.load()[ReadAloudControlsNumber.Width])
            assertEquals(432, preferences.getInt(PreferKey.readAloudControlsWidth, -1))
            ReadAloudControlsToggle.entries.forEach {
                assertEquals(
                    it != ReadAloudControlsToggle.Pause && it.defaultValue,
                    preferences.getBoolean(it.key, !it.defaultValue),
                )
            }
            assertEquals(90, repository.load()[ReadAloudControlsNumber.Opacity])
            assertEquals(100, repository.load()[ReadAloudControlsNumber.Threshold])
            preferences
                .edit()
                .putInt(PreferKey.readAloudControlsWidth, 40)
                .putInt(PreferKey.readAloudControlsOpacity, 30)
                .putInt(PreferKey.readAloudControlsThreshold, 999)
                .putString(PreferKey.readAloudControlsDrag, "true")
                .commit()
            val reopened = PreferenceReadAloudControlsSettingsRepository(context)
            assertEquals(85, preferences.getInt(PreferKey.readAloudControlsWidth, -1))
            assertEquals(30, reopened.load()[ReadAloudControlsNumber.Opacity])
            assertEquals(500, preferences.getInt(PreferKey.readAloudControlsThreshold, -1))
            assertTrue(reopened.load()[ReadAloudControlsToggle.Drag])
            reopened.setNumber(ReadAloudControlsNumber.Opacity, -20)
            assertEquals(0, preferences.getInt(PreferKey.readAloudControlsOpacity, -1))
            reopened.setToggle(ReadAloudControlsToggle.Pause, true)
            assertTrue(preferences.getBoolean(PreferKey.readAloudControlsPause, false))
        } finally {
            preferences
                .edit()
                .apply {
                    previous.forEach { (key, value) ->
                        when (value) {
                            null -> remove(key)
                            is Boolean -> putBoolean(key, value)
                            is Int -> putInt(key, value)
                            is String -> putString(key, value)
                            is Float -> putFloat(key, value)
                            is Long -> putLong(key, value)
                            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                }
                .commit()
        }
    }
}
