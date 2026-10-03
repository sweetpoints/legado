package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import io.legado.app.constant.EventBus
import io.legado.app.data.file.SettingsImageInstaller
import io.legado.app.model.BookCover
import io.legado.app.model.cover.*
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.postEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

internal class AppCoverSettingsStore(context: Context) : CoverSettingsStore {
    private val application = context.applicationContext
    private val preferences by lazy { application.defaultSharedPreferences }
    private val installer = SettingsImageInstaller(application, "covers")
    override fun changes() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener); trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    override suspend fun load(): CoverSettingsSnapshot {
        val values = preferences.all
        return CoverSettingsSnapshot(CoverSettingSwitch.entries.associateWith { values[it.key] as? Boolean ?: it.default },
            CoverSettingImage.entries.associateWith { values[it.key] as? String ?: "" })
    }
    private fun save(editor: SharedPreferences.Editor) { check(editor.commit()) { "Unable to save cover settings" } }
    override suspend fun boolean(key: CoverSettingSwitch, value: Boolean) = save(preferences.edit().putBoolean(key.key, value))
    override suspend fun stageImage(uri: String) = installer.stage(uri)
    override suspend fun image(key: CoverSettingImage, path: String?) = save(
        if (path == null) preferences.edit().remove(key.key) else preferences.edit().putString(key.key, path))
    override suspend fun refreshCover() { BookCover.upDefaultCover() }
    override suspend fun refreshBookshelf() { postEvent(EventBus.BOOKSHELF_REFRESH, "") }
}
