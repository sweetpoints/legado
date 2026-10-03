package io.legado.app.data.preferences

import io.legado.app.help.storage.BackupConfig
import io.legado.app.model.backup.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AppBackupChoicesStoreTest {
    @Test
    fun actualBackupConfigKeepsSensitiveContentOptInAndIgnoreCheckedSenseDistinct() = runBlocking {
        withContext(Dispatchers.IO) {
            val previous =
                synchronized(BackupConfig.ignoreConfig) { BackupConfig.ignoreConfig.toMap() }
            try {
                synchronized(BackupConfig.ignoreConfig) { BackupConfig.ignoreConfig.clear() }
                val store = AppBackupChoicesStore()
                val initial = store.load(BackupChoiceGroup.Content)
                assertEquals(BackupConfig.contentKeys.toList(), initial.map { it.key })
                val optIn =
                    setOf(
                        BackupConfig.cookieContentKey,
                        BackupConfig.runtimeSourceCacheContentKey,
                        BackupConfig.readRecordCoverContentKey,
                    )
                initial.forEach { assertEquals(it.key !in optIn, it.checked) }
                val cookies = BackupConfig.cookieContentKey
                store.toggle(BackupChoiceGroup.Content, cookies, true)
                assertEquals(false, BackupConfig.ignoreConfig[cookies])
                assertTrue(
                    store.load(BackupChoiceGroup.Content).first { it.key == cookies }.checked
                )
                val ignore = store.load(BackupChoiceGroup.Ignore)
                assertEquals(BackupConfig.ignoreKeys.toList(), ignore.map { it.key })
                assertTrue(ignore.all { !it.checked })
                val key = ignore.first().key
                store.toggle(BackupChoiceGroup.Ignore, key, true)
                assertEquals(true, BackupConfig.ignoreConfig[key])
                assertTrue(store.load(BackupChoiceGroup.Ignore).first().checked)
                assertFalse(initial.first { it.key == cookies }.checked)
            } finally {
                synchronized(BackupConfig.ignoreConfig) {
                    BackupConfig.ignoreConfig.clear()
                    BackupConfig.ignoreConfig.putAll(previous)
                }
            }
        }
    }
}
