package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getBooleanCompat

data class MyPreferences(
    val moreItems: Set<String>,
    val themeMode: String,
    val autoTaskEnabled: Boolean,
    val webEnabled: Boolean,
    val mcpEnabled: Boolean,
)

/** Retains the existing backup-compatible keys; no UI or service side effects. */
class MySettingsRepository(context: Context) {
    private val preferences = context.applicationContext.defaultSharedPreferences

    fun read(): MyPreferences = MyPreferences(
        moreItems = preferences.getStringSet(PreferKey.myMoreItems, setOf("check_update", "check_beta_update")).orEmpty().toSet(),
        themeMode = preferences.getString(PreferKey.themeMode, "0") ?: "0",
        autoTaskEnabled = preferences.getBooleanCompat(PreferKey.autoTaskService, false),
        webEnabled = preferences.getBooleanCompat(PreferKey.webService, false),
        mcpEnabled = preferences.getBooleanCompat(PreferKey.mcpService, false),
    )

    fun setMoreItems(keys: Set<String>) = preferences.edit { putStringSet(PreferKey.myMoreItems, keys.toSet()) }
    fun setThemeMode(value: String) = preferences.edit { putString(PreferKey.themeMode, value) }
    fun setSwitch(key: String, enabled: Boolean) = preferences.edit { putBoolean(key, enabled) }

    fun observeChanges(onChange: (String?) -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onChange(key) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
