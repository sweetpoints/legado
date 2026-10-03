package io.legado.app.data.preferences

import io.legado.app.help.storage.BackupConfig
import io.legado.app.model.backup.*

internal class AppBackupChoicesStore : BackupChoicesStore {
    override suspend fun load(group: BackupChoiceGroup): List<BackupChoice> =
        synchronized(BackupConfig.ignoreConfig) {
            when (group) {
                BackupChoiceGroup.Content ->
                    BackupConfig.contentKeys.mapIndexed { index, key ->
                        BackupChoice(
                            key,
                            BackupConfig.contentTitles[index],
                            BackupConfig.contentIsEnabled(key),
                        )
                    }
                BackupChoiceGroup.Ignore ->
                    BackupConfig.ignoreKeys.mapIndexed { index, key ->
                        BackupChoice(
                            key,
                            BackupConfig.ignoreTitle[index],
                            BackupConfig.ignoreConfig[key] ?: false,
                        )
                    }
            }
        }

    override suspend fun toggle(group: BackupChoiceGroup, key: String, checked: Boolean) {
        synchronized(BackupConfig.ignoreConfig) {
            BackupConfig.ignoreConfig[key] =
                if (group == BackupChoiceGroup.Content) !checked else checked
        }
    }

    override suspend fun save() {
        synchronized(BackupConfig.ignoreConfig) { BackupConfig.saveIgnoreConfig() }
    }
}
