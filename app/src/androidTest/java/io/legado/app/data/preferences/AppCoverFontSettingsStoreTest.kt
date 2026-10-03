package io.legado.app.data.preferences

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.legado.app.model.cover.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class AppCoverFontSettingsStoreTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val id = "cover-font-test-${UUID.randomUUID()}"
    private val directory = File(application.cacheDir, id)
    private val context =
        object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int) =
                application.getSharedPreferences(id, mode)

            override fun getExternalFilesDir(type: String?): File = directory
        }

    @After
    fun clear() {
        application.getSharedPreferences(id, 0).edit().clear().commit()
        directory.deleteRecursively()
    }

    @Test
    fun actualPreferencesUsePercentDefaultsAndFontInstallerRejectsInvalidBytesWithoutPublishing() =
        runBlocking {
            withContext(Dispatchers.IO) {
                val store = AppCoverFontSettingsStore(context)
                val initial = store.load()
                assertFalse(initial.customSizesEnabled)
                assertTrue(initial.switches.getValue(CoverFontSwitch.Adaptive))
                assertEquals("", initial.fontPath)
                CoverFontSize.entries.forEach { assertEquals(100, initial.sizes.getValue(it)) }
                store.size(CoverFontSize.TitleLarge, 234)
                store.size(CoverFontSize.AuthorSmall, 13)
                store.boolean(CoverFontSwitch.CustomSize, true)
                assertEquals(200, store.load().sizes.getValue(CoverFontSize.TitleLarge))
                assertEquals(50, store.load().sizes.getValue(CoverFontSize.AuthorSmall))
                directory.mkdirs()
                val bad = File(directory, "bad.ttf").apply { writeText("not a font") }
                assertTrue(runCatching { store.stageFont(bad.path) }.isFailure)
                assertEquals("", store.load().fontPath)
                val system =
                    File("/system/fonts").listFiles().orEmpty().first { it.extension == "ttf" }
                val installed = File(store.stageFont(system.path))
                assertArrayEquals(system.readBytes(), installed.readBytes())
                assertEquals(File(directory, "font"), installed.parentFile)
                assertEquals("", store.load().fontPath)
                store.font(installed.path)
                assertEquals(installed.path, store.load().fontPath)
                store.font("")
                assertTrue(installed.exists())
                assertEquals("", store.load().fontPath)
                assertEquals(200, store.load().sizes.getValue(CoverFontSize.TitleLarge))
            }
        }
}
