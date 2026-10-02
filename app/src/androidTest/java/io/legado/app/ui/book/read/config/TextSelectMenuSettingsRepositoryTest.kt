package io.legado.app.ui.book.read.config

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.preferences.PreferenceTextSelectMenuSettingsRepository
import io.legado.app.help.TextSelectMenuConfig
import io.legado.app.utils.defaultSharedPreferences
import org.junit.Assert.*
import org.junit.Test

class TextSelectMenuSettingsRepositoryTest {
    @Test fun legacyMigrationAndJsonRoundTripRetainAllSelectionActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.defaultSharedPreferences
        val keys = listOf(PreferKey.textSelectMenuConfig, PreferKey.expandTextMenu)
        val previous = keys.associateWith { preferences.all[it] }
        try {
            preferences.edit().remove(PreferKey.textSelectMenuConfig).putBoolean(PreferKey.expandTextMenu, true).commit()
            val repository = PreferenceTextSelectMenuSettingsRepository(context)
            assertEquals(TextSelectMenuConfig(TextSelectMenuConfig.ALL_KEYS, emptyList()), repository.load())
            assertTrue(preferences.contains(PreferKey.textSelectMenuConfig))
            val config = TextSelectMenuConfig(listOf("copy", "dict"), TextSelectMenuConfig.ALL_KEYS.filterNot { it in listOf("copy", "dict") })
            repository.save(config)
            assertEquals(config, TextSelectMenuConfig.fromJson(preferences.getString(PreferKey.textSelectMenuConfig, null)))
            assertEquals(config, repository.load())
            preferences.edit().putString(PreferKey.textSelectMenuConfig, "{broken").commit()
            assertEquals(TextSelectMenuConfig.default(), repository.load())
            preferences.edit().putString(PreferKey.textSelectMenuConfig, "{\"bar\":[\"copy\",\"copy\",\"future\"],\"more\":[]}").commit()
            val normalized = repository.load()
            assertEquals(listOf("copy"), normalized.bar)
            assertEquals(10, normalized.bar.size + normalized.more.size)
            assertEquals(TextSelectMenuConfig.ALL_KEYS.toSet(), (normalized.bar + normalized.more).toSet())
        } finally {
            preferences.edit().apply { previous.forEach { (key, value) ->
                when (value) { null -> remove(key); is Boolean -> putBoolean(key, value); is String -> putString(key, value) }
            } }.commit()
        }
    }
}
