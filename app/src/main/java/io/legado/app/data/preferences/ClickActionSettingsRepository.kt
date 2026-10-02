package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.defaultSharedPreferences

enum class ClickActionRegion(val key: String, val defaultAction: Int) {
    TopLeft(PreferKey.clickActionTL, 2), TopCenter(PreferKey.clickActionTC, 2), TopRight(PreferKey.clickActionTR, 1),
    MiddleLeft(PreferKey.clickActionML, 2), MiddleCenter(PreferKey.clickActionMC, 0), MiddleRight(PreferKey.clickActionMR, 1),
    BottomLeft(PreferKey.clickActionBL, 2), BottomCenter(PreferKey.clickActionBC, 1), BottomRight(PreferKey.clickActionBR, 1),
}
interface ClickActionSettingsRepository {
    fun load(): Map<ClickActionRegion, Int>
    fun setAction(region: ClickActionRegion, action: Int)
    fun ensureMenuAction()
    fun observe(onChange: () -> Unit): AutoCloseable
}
class PreferenceClickActionSettingsRepository(context: Context) : ClickActionSettingsRepository {
    private val preferences = context.applicationContext.defaultSharedPreferences
    override fun load() = ClickActionRegion.entries.associateWith { preferences.getInt(it.key, it.defaultAction) }
    override fun setAction(region: ClickActionRegion, action: Int) { preferences.edit { putInt(region.key, action) } }
    override fun ensureMenuAction() {
        // Preserve the original safety rule: one of the nine areas must still open the reader menu.
        if (load().values.none { it == 0 }) setAction(ClickActionRegion.MiddleCenter, 0)
        AppConfig.detectClickArea()
    }
    override fun observe(onChange: () -> Unit): AutoCloseable {
        val keys = ClickActionRegion.entries.map { it.key }.toSet()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == null || key in keys) onChange() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
