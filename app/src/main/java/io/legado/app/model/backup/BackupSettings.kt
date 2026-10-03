package io.legado.app.model.backup

import io.legado.app.constant.PreferKey

internal enum class BackupSettingText(val key: String, val reconfigure: Boolean) {
    Url(PreferKey.webDavUrl, true), Account(PreferKey.webDavAccount, true), Password(PreferKey.webDavPassword, true),
    Directory(PreferKey.webDavDir, true), Device(PreferKey.webDavDeviceName, false)
}
internal enum class BackupSettingSwitch(val key: String, val default: Boolean) {
    BookRestore(PreferKey.webDavBookAutoRestore, false), Progress(PreferKey.syncBookProgress, true),
    ProgressPlus(PreferKey.syncBookProgressPlus, false), Latest(PreferKey.onlyLatestBackup, true), CheckNew(PreferKey.autoCheckNewBackup, true)
}
internal data class AutoBackupSettings(val enabled: Boolean = true, val webDav: Boolean = true, val intervalDays: Int = 1)
internal data class BackupSettingsSnapshot(val texts: Map<BackupSettingText, String> = BackupSettingText.entries.associateWith { if (it == BackupSettingText.Directory) "legado" else "" },
    val switches: Map<BackupSettingSwitch, Boolean> = BackupSettingSwitch.entries.associateWith { it.default },
    val backupPath: String? = null, val defaultPath: String = "", val automatic: AutoBackupSettings = AutoBackupSettings()) {
    fun enabled(key: BackupSettingSwitch) = key != BackupSettingSwitch.ProgressPlus || switches.getValue(BackupSettingSwitch.Progress)
}
