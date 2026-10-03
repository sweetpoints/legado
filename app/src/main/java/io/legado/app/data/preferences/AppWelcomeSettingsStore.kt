package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import io.legado.app.constant.PreferKey
import io.legado.app.data.file.SettingsImageInstaller
import io.legado.app.data.file.isStoredSettingsImage
import io.legado.app.model.BookCover
import io.legado.app.model.welcome.*
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

internal class AppWelcomeSettingsStore(context: Context) : WelcomeSettingsStore {
    private val application = context.applicationContext
    private val preferences by lazy { application.defaultSharedPreferences }
    private val images = SettingsImageInstaller(application, "covers")

    override fun changes() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override suspend fun load(): WelcomeSettingsSnapshot {
        val values = preferences.all
        return WelcomeSettingsSnapshot(
            (values[PreferKey.welcomeShowTime] as? Int ?: 500).coerceIn(0, 800),
            WelcomeSwitch.entries.associateWith { values[it.key] as? Boolean ?: it.default },
            values[PreferKey.welcomeImage] as? String ?: "",
            values[PreferKey.welcomeImageDark] as? String ?: "",
        )
    }

    private fun save(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "Unable to save welcome settings" }
    }

    override suspend fun milliseconds(value: Int) =
        save(preferences.edit().putInt(PreferKey.welcomeShowTime, value.coerceIn(0, 800)))

    override suspend fun boolean(key: WelcomeSwitch, value: Boolean) =
        save(preferences.edit().putBoolean(key.key, value))

    override suspend fun stageImage(uri: String) = images.stage(uri)

    override suspend fun image(night: Boolean, path: String?) {
        val key = if (night) PreferKey.welcomeImageDark else PreferKey.welcomeImage
        save(
            if (path == null) preferences.edit().remove(key)
            else preferences.edit().putString(key, path)
        )
    }

    override suspend fun removeOwnedImage(path: String) {
        // The shared covers folder also serves other day/night settings. Preserve any live
        // preference reference.
        if (
            isStoredSettingsImage(application.externalFiles, "covers", path) &&
                preferences.all.values.none { it == path }
        ) {
            val file = File(path)
            check(!file.exists() || file.delete()) { "Unable to remove previous welcome image" }
        }
    }

    override suspend fun refreshCover() {
        BookCover.upDefaultCover()
    }
}
