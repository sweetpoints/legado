package io.legado.app.data.preferences

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CodeSettingsSnapshot(val font: Int = 16, val autoComplete: Boolean = true, val nonPrintable: Int = 0)
interface CodeSettingsPreferences {
    suspend fun load(): CodeSettingsSnapshot
    fun saveFont(value: Int)
    fun saveAutoComplete(value: Boolean)
    fun saveNonPrintable(value: Int)
}
class AppCodeSettingsPreferences(context: Context) : CodeSettingsPreferences {
    private val context = context.applicationContext
    override suspend fun load() = withContext(Dispatchers.IO) {
        CodeSettingsSnapshot(AppConfig.editFontScale, AppConfig.editAutoComplete, AppConfig.editNonPrintable)
    }
    // apply() updates memory immediately and queues the disk write.
    override fun saveFont(value: Int) { context.putPrefInt(PreferKey.editFontScale, value) }
    override fun saveAutoComplete(value: Boolean) { context.putPrefBoolean(PreferKey.editAutoComplete, value) }
    override fun saveNonPrintable(value: Int) { context.putPrefInt(PreferKey.editNonPrintable, value) }
}
