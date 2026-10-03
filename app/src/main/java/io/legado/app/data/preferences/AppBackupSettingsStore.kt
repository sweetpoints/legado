package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.backup.*
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

internal class AppBackupSettingsStore(context: Context) : BackupSettingsStore {
    private val application = context.applicationContext
    private val preferences by lazy { application.defaultSharedPreferences }

    override fun changes() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override suspend fun load(): BackupSettingsSnapshot {
        val values = preferences.all
        return BackupSettingsSnapshot(
            BackupSettingText.entries.associateWith { key ->
                values[key.key] as? String
                    ?: when (key) {
                        BackupSettingText.Directory -> "legado"
                        BackupSettingText.Device -> Build.MODEL
                        else -> ""
                    }
            },
            BackupSettingSwitch.entries.associateWith { values[it.key] as? Boolean ?: it.default },
            values[PreferKey.backupPath] as? String,
            application.externalFiles.absolutePath,
            AutoBackupSettings(
                values[PreferKey.autoBackup] as? Boolean ?: true,
                values[PreferKey.autoBackupWebDav] as? Boolean ?: true,
                (values[PreferKey.autoBackupIntervalDays] as? Int ?: 1).coerceAtLeast(1),
            ),
        )
    }

    private fun save(editor: SharedPreferences.Editor) {
        check(editor.commit()) { "Unable to save backup settings" }
    }

    override suspend fun text(key: BackupSettingText, value: String) =
        save(preferences.edit().putString(key.key, value))

    override suspend fun boolean(key: BackupSettingSwitch, value: Boolean) =
        save(preferences.edit().putBoolean(key.key, value))

    override suspend fun automatic(value: AutoBackupSettings) =
        save(
            preferences
                .edit()
                .putBoolean(PreferKey.autoBackup, value.enabled)
                .putBoolean(PreferKey.autoBackupWebDav, value.webDav)
                .putInt(PreferKey.autoBackupIntervalDays, value.intervalDays)
        )

    override suspend fun path(value: String?) =
        save(
            if (value == null) preferences.edit().remove(PreferKey.backupPath)
            else preferences.edit().putString(PreferKey.backupPath, value)
        )

    override suspend fun localPassword(value: String) {
        LocalConfig.password = value
    }

    override suspend fun reconfigureWebDav() {
        AppWebDav.upConfig()
    }

    override suspend fun needsHelp() = !LocalConfig.backupHelpVersionIsLast
}
