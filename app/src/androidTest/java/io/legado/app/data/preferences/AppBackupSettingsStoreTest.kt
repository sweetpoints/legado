package io.legado.app.data.preferences

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.PreferKey
import io.legado.app.model.backup.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class AppBackupSettingsStoreTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val id = "backup-settings-test-${UUID.randomUUID()}"
    private val directory = File(application.cacheDir, id)
    private val preferences = application.getSharedPreferences(id, 0)
    private val context =
        object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int) = preferences

            override fun getExternalFilesDir(type: String?): File = directory
        }

    @After
    fun cleanup() {
        preferences.edit().clear().commit()
        directory.deleteRecursively()
    }

    @Test
    fun actualIsolatedPreferencesKeepConnectionDefaultsDependencyAndAtomicAutomaticDestination() =
        runBlocking {
            withContext(Dispatchers.IO) {
                val store = AppBackupSettingsStore(context)
                val initial = store.load()
                assertEquals("legado", initial.texts.getValue(BackupSettingText.Directory))
                assertEquals(Build.MODEL, initial.texts.getValue(BackupSettingText.Device))
                assertEquals(directory.absolutePath, initial.defaultPath)
                assertNull(initial.backupPath)
                assertEquals(AutoBackupSettings(), initial.automatic)
                assertTrue(initial.switches.getValue(BackupSettingSwitch.Progress))
                assertFalse(initial.switches.getValue(BackupSettingSwitch.ProgressPlus))
                val automatic = AutoBackupSettings(false, false, 42)
                store.automatic(automatic)
                assertEquals(automatic, store.load().automatic)
                assertEquals(42, preferences.getInt(PreferKey.autoBackupIntervalDays, -1))
                store.text(BackupSettingText.Directory, "")
                assertEquals("", store.load().texts.getValue(BackupSettingText.Directory))
                store.path("content://fixture/tree")
                assertEquals("content://fixture/tree", store.load().backupPath)
                store.path(null)
                assertNull(store.load().backupPath)
                store.boolean(BackupSettingSwitch.ProgressPlus, true)
                store.boolean(BackupSettingSwitch.Progress, false)
                assertFalse(store.load().enabled(BackupSettingSwitch.ProgressPlus))
                assertTrue(store.load().switches.getValue(BackupSettingSwitch.ProgressPlus))
            }
        }
}
