package io.legado.app.ui.book.read.config

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.preferences.PreferenceReadAloudSettingsRepository
import io.legado.app.data.preferences.ReadAloudSwitch
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.defaultSharedPreferences
import org.junit.Assert.*
import org.junit.Test

class ReadAloudSettingsRepositoryTest {
    @Test
    fun legacyBooleanValuesAndStartModeRetainExistingBackupKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.defaultSharedPreferences
        val keys = ReadAloudSwitch.entries.map { it.key } + PreferKey.readAloudStart
        val previous = keys.associateWith { preferences.all[it] }
        try {
            preferences.edit().apply { keys.forEach { remove(it) } }.commit()
            val repository = PreferenceReadAloudSettingsRepository(context)
            assertFalse(repository.load().switches.values.any { it })
            assertEquals("sentence", repository.load().start)
            preferences
                .edit()
                .putString(PreferKey.ignoreAudioFocus, "true")
                .putString(PreferKey.readAloudByPage, "false")
                .commit()
            assertTrue(repository.load()[ReadAloudSwitch.IgnoreAudioFocus])
            assertFalse(repository.load()[ReadAloudSwitch.ByPage])
            repository.setSwitch(ReadAloudSwitch.StreamAudio, true)
            assertTrue(preferences.getBoolean(PreferKey.streamReadAloudAudio, false))
            repository.setStart("page")
            assertEquals("page", preferences.getString(PreferKey.readAloudStart, null))
            assertFalse(AppConfig.readAloudStartAtSentence)
            preferences.edit().putString(PreferKey.readAloudStart, "future-mode").commit()
            assertEquals("sentence", repository.load().start)
        } finally {
            preferences
                .edit()
                .apply {
                    previous.forEach { (key, value) ->
                        when (value) {
                            null -> remove(key)
                            is Boolean -> putBoolean(key, value)
                            is String -> putString(key, value)
                        }
                    }
                }
                .commit()
        }
    }
}
