package io.legado.app.data.preferences

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.legado.app.model.cover.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class AppCoverSettingsStoreTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val id = "cover-settings-test-${UUID.randomUUID()}"
    private val directory = File(application.cacheDir, id)
    private val context = object : ContextWrapper(application) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = application.getSharedPreferences(id, mode)
        override fun getExternalFilesDir(type: String?): File = directory
    }
    @After fun cleanup() { application.getSharedPreferences(id, 0).edit().clear().commit(); directory.deleteRecursively() }
    @Test fun actualPreferencesKeepFourImageKeysAndBothIndependentAuthorValuesWithoutDeletingInstalledFiles() = runBlocking {
        withContext(Dispatchers.IO) {
            val store = AppCoverSettingsStore(context); val initial = store.load()
            assertFalse(initial.switches.getValue(CoverSettingSwitch.Wifi)); assertFalse(initial.switches.getValue(CoverSettingSwitch.Default))
            assertTrue(initial.switches.getValue(CoverSettingSwitch.DayName)); assertTrue(initial.switches.getValue(CoverSettingSwitch.NightAuthor))
            store.boolean(CoverSettingSwitch.DayName, false); store.boolean(CoverSettingSwitch.NightAuthor, false)
            assertFalse(store.load().enabled(CoverSettingSwitch.DayAuthor)); assertTrue(store.load().switches.getValue(CoverSettingSwitch.DayAuthor))
            directory.mkdirs(); val input = File(directory, "source.9.png").apply { writeBytes(ByteArray(8197) { (it % 253).toByte() }) }
            val installed = File(store.stageImage(Uri.fromFile(input).toString()))
            assertTrue(installed.name.endsWith(".9.png")); assertArrayEquals(input.readBytes(), installed.readBytes())
            assertEquals(File(directory, "covers").canonicalFile, installed.canonicalFile.parentFile)
            CoverSettingImage.entries.forEach { store.image(it, installed.path) }
            store.image(CoverSettingImage.RecordNight, null)
            val actual = store.load(); assertEquals("", actual.images.getValue(CoverSettingImage.RecordNight))
            assertEquals(installed.path, actual.images.getValue(CoverSettingImage.RecordDay)); assertTrue(installed.exists())
            assertFalse(actual.switches.getValue(CoverSettingSwitch.NightAuthor)); assertTrue(actual.switches.getValue(CoverSettingSwitch.DayAuthor))
        }
    }
}
