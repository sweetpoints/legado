package io.legado.app.data.preferences

import android.content.Context
import androidx.core.content.edit
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences

data class PageKeyValues(val previous: String = "", val next: String = "")

interface PageKeySettingsRepository {
    fun load(): PageKeyValues

    fun save(values: PageKeyValues)
}

class PreferencePageKeySettingsRepository(context: Context) : PageKeySettingsRepository {
    private val preferences = context.applicationContext.defaultSharedPreferences

    override fun load() =
        PageKeyValues(
            preferences.getString(PreferKey.prevKeys, "").orEmpty(),
            preferences.getString(PreferKey.nextKeys, "").orEmpty(),
        )

    override fun save(values: PageKeyValues) {
        preferences.edit {
            putString(PreferKey.prevKeys, values.previous)
            putString(PreferKey.nextKeys, values.next)
        }
    }
}
