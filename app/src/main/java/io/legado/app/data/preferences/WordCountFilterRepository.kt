package io.legado.app.data.preferences

import android.content.Context
import androidx.core.content.edit
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences

data class WordCountFilterSettings(val mode: Int = 0, val minimum: Int = 0, val maximum: Int = 0)
interface WordCountFilterRepository {
    fun load(): WordCountFilterSettings
    fun save(settings: WordCountFilterSettings)
}
class PreferenceWordCountFilterRepository(context: Context) : WordCountFilterRepository {
    private val preferences = context.applicationContext.defaultSharedPreferences
    override fun load() = WordCountFilterSettings(preferences.getInt(PreferKey.changeSourceWordCountFilterMode, 0).coerceIn(0, 2),
        preferences.getInt(PreferKey.changeSourceWordCountFilterMin, 0), preferences.getInt(PreferKey.changeSourceWordCountFilterMax, 0))
    override fun save(settings: WordCountFilterSettings) { preferences.edit {
        putInt(PreferKey.changeSourceWordCountFilterMode, settings.mode)
        putInt(PreferKey.changeSourceWordCountFilterMin, settings.minimum)
        putInt(PreferKey.changeSourceWordCountFilterMax, settings.maximum)
    } }
}
