package io.legado.app.data.preferences

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.legado.app.model.theme.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class AppThemeSettingsStoreTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val id = "theme-settings-test-${UUID.randomUUID()}"
    private val folder = File(application.cacheDir, id)
    private val context =
        object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int) =
                application.getSharedPreferences(id, mode)

            override fun getExternalFilesDir(type: String?): File = folder
        }

    @After
    fun cleanup() {
        application.getSharedPreferences(id, 0).edit().clear().commit()
        folder.deleteRecursively()
    }

    @Test
    fun actualPreferencesSnapshotDefaultsObserveAndStoreAllTypedColorsAndSwitches() = runBlocking {
        withContext(Dispatchers.IO) {
            val store = AppThemeSettingsStore(context)
            val initial = store.load()
            assertEquals(8, initial.colors.size)
            assertEquals(7, initial.switches.size)
            assertEquals("ic_launcher", initial.launcher)
            assertEquals(0, initial.fontScale)
            ThemeSwitch.entries.forEachIndexed { index, key -> store.boolean(key, index % 2 == 0) }
            ThemeColor.entries.forEachIndexed { index, key ->
                store.color(key, 0xff000000.toInt() + index)
            }
            store.font(14)
            store.elevation(7)
            store.launcher("Launcher5")
            store.changes().first()
            val saved = store.load()
            ThemeSwitch.entries.forEachIndexed { index, key ->
                assertEquals(index % 2 == 0, saved.switches.getValue(key))
            }
            ThemeColor.entries.forEachIndexed { index, key ->
                assertEquals(0xff000000.toInt() + index, saved.colors.getValue(key))
            }
            assertEquals(14, saved.fontScale)
            assertEquals("Launcher5", saved.launcher)
        }
    }

    @Test
    fun actualLocalBackgroundCopiesFullBytesWithNinePatchSuffixAndKeepsDayNightIndependent() =
        runBlocking {
            withContext(Dispatchers.IO) {
                folder.mkdirs()
                val source = File(folder, "incoming.9.png")
                val bytes = ByteArray(8193) { (it % 251).toByte() }
                source.writeBytes(bytes)
                val store = AppThemeSettingsStore(context)
                val path = store.stageImage(false, Uri.fromFile(source).toString())
                assertTrue(path.endsWith(".9.png"))
                assertArrayEquals(bytes, File(path).readBytes())
                assertEquals(
                    "",
                    store.load().dayImage,
                ) // Staging alone never publishes an unfinished selection.
                store.image(false, path)
                store.image(true, "independent-night")
                assertEquals(path, store.load().dayImage)
                assertEquals("independent-night", store.load().nightImage)
                store.image(false, null)
                assertEquals("", store.load().dayImage)
                assertEquals("independent-night", store.load().nightImage)
                assertArrayEquals(
                    bytes,
                    File(path).readBytes(),
                ) // Removing a preference preserves the user's image file.
            }
        }
}
