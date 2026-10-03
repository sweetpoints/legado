package io.legado.app.data.preferences

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CodeThemeSnapshot(val automatic: Boolean = false, val light: Int = 0, val dark: Int = 0)

interface CodeThemePreferences {
    suspend fun load(): CodeThemeSnapshot

    fun saveAutomatic(value: Boolean)

    fun saveTheme(dark: Boolean, index: Int)
}

class AppCodeThemePreferences(context: Context) : CodeThemePreferences {
    private val context = context.applicationContext

    override suspend fun load() =
        withContext(Dispatchers.IO) {
            CodeThemeSnapshot(AppConfig.editTemeAuto, AppConfig.editTheme, AppConfig.editThemeDark)
        }

    override fun saveAutomatic(value: Boolean) {
        context.putPrefBoolean(PreferKey.editTemeAuto, value)
    }

    override fun saveTheme(dark: Boolean, index: Int) {
        context.putPrefInt(if (dark) PreferKey.editThemeDark else PreferKey.editTheme, index)
    }
}
