package io.legado.app.ui.book.read.config

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.preferences.ClickActionRegion
import io.legado.app.data.preferences.PreferenceClickActionSettingsRepository
import io.legado.app.utils.defaultSharedPreferences
import org.junit.Assert.*
import org.junit.Test

class ClickActionSettingsRepositoryTest {
    @Test
    fun originalIntegerKeysDriveImmediateReaderActionsAndCloseFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.defaultSharedPreferences
        val previous = ClickActionRegion.entries.associate { it.key to preferences.all[it.key] }
        instrumentation.runOnMainSync {
            try {
                preferences
                    .edit()
                    .apply { ClickActionRegion.entries.forEach { remove(it.key) } }
                    .commit()
                val repository = PreferenceClickActionSettingsRepository(context)
                assertEquals(
                    listOf(2, 2, 1, 2, 0, 1, 2, 1, 1),
                    ClickActionRegion.entries.map { repository.load()[it] },
                )
                repository.setAction(ClickActionRegion.MiddleCenter, 13)
                assertEquals(13, preferences.getInt(ClickActionRegion.MiddleCenter.key, -99))
                repository.ensureMenuAction()
                assertEquals(0, preferences.getInt(ClickActionRegion.MiddleCenter.key, -99))
                repository.setAction(ClickActionRegion.TopLeft, 0)
                repository.setAction(ClickActionRegion.MiddleCenter, -1)
                repository.ensureMenuAction()
                assertEquals(-1, preferences.getInt(ClickActionRegion.MiddleCenter.key, -99))
                assertEquals(0, preferences.getInt(ClickActionRegion.TopLeft.key, -99))
            } finally {
                preferences
                    .edit()
                    .apply {
                        previous.forEach { (key, value) ->
                            when (value) {
                                null -> remove(key)
                                is Int -> putInt(key, value)
                                is String -> putString(key, value)
                            }
                        }
                    }
                    .commit()
            }
        }
    }
}
