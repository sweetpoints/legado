package io.legado.app.data.preferences

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.PreferKey
import io.legado.app.model.welcome.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class AppWelcomeSettingsStoreTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val id = "welcome-settings-test-${UUID.randomUUID()}"
    private val directory = File(application.cacheDir, id)
    private val context = object : ContextWrapper(application) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = application.getSharedPreferences(id, mode)
        override fun getExternalFilesDir(type: String?): File = directory
    }
    @After fun cleanup() { application.getSharedPreferences(id, 0).edit().clear().commit(); directory.deleteRecursively() }
    @Test fun actualPreferenceDefaultsAndIndependentFlagsKeepMillisecondUnitsAndNoImageCoupling() = runBlocking {
        withContext(Dispatchers.IO) {
            val store = AppWelcomeSettingsStore(context); val initial = store.load()
            assertEquals(500, initial.milliseconds); assertEquals("", initial.dayImage); assertFalse(initial.switches.getValue(WelcomeSwitch.Custom))
            assertTrue(initial.switches.getValue(WelcomeSwitch.DayText)); assertTrue(initial.switches.getValue(WelcomeSwitch.NightIcon))
            store.milliseconds(537); store.boolean(WelcomeSwitch.NightText, false); store.boolean(WelcomeSwitch.Custom, true)
            val saved = store.load(); assertEquals(537, saved.milliseconds); assertFalse(saved.switches.getValue(WelcomeSwitch.NightText))
            assertTrue(saved.switches.getValue(WelcomeSwitch.DayText)); assertTrue(saved.switches.getValue(WelcomeSwitch.NightIcon))
        }
    }
    @Test fun actualImageAtomicCopiesBytesIntoCoversWithNinePatchNameAndPreservesSharedReferencesAndOutsideFiles() = runBlocking {
        withContext(Dispatchers.IO) {
            directory.mkdirs(); val source = File(directory, "input.9.png").apply { writeBytes(ByteArray(8203) { (it % 251).toByte() }) }
            val store = AppWelcomeSettingsStore(context); val file = File(store.stageImage(Uri.fromFile(source).toString()))
            assertTrue(file.name.endsWith(".9.png")); assertEquals(File(directory, "covers").canonicalFile, file.canonicalFile.parentFile)
            assertArrayEquals(source.readBytes(), file.readBytes()); assertEquals("", store.load().dayImage)
            store.image(false, file.path); store.image(true, file.path); store.image(false, null); store.removeOwnedImage(file.path)
            assertTrue(file.exists()); assertEquals(file.path, store.load().nightImage)
            store.image(true, null); application.getSharedPreferences(id, 0).edit().putString(PreferKey.defaultCover, file.path).commit()
            store.removeOwnedImage(file.path); assertTrue(file.exists())
            application.getSharedPreferences(id, 0).edit().remove(PreferKey.defaultCover).commit(); store.removeOwnedImage(file.path); assertFalse(file.exists())
            store.removeOwnedImage(source.path); assertTrue(source.exists())
        }
    }
}
