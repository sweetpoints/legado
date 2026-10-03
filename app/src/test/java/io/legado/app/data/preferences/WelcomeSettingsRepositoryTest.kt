package io.legado.app.data.preferences

import io.legado.app.data.file.isStoredSettingsImage
import io.legado.app.model.welcome.*
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.Assert.*

class WelcomeSettingsRepositoryTest {
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @After
    fun close() {
        io.close()
    }

    private class Store : WelcomeSettingsStore {
        var value =
            WelcomeSettingsSnapshot(dayImage = "owned-old", nightImage = "independent-night")
        val calls = mutableListOf<String>()
        val threads = mutableListOf<Thread>()
        var failStage = false
        var failWrite = false

        private fun call(value: String) {
            calls += value
            threads += Thread.currentThread()
        }

        override fun changes() = flowOf(Unit)

        override suspend fun load(): WelcomeSettingsSnapshot {
            call("load")
            return value
        }

        override suspend fun milliseconds(value: Int) {
            call("ms:$value")
            this.value = this.value.copy(milliseconds = value)
        }

        override suspend fun boolean(key: WelcomeSwitch, value: Boolean) {
            call("bool:${key.name}:$value")
            this.value = this.value.copy(switches = this.value.switches + (key to value))
        }

        override suspend fun stageImage(uri: String): String {
            call("stage:$uri")
            if (failStage) error("stage failed")
            return uri
        }

        override suspend fun image(night: Boolean, path: String?) {
            call("image:$night:$path")
            if (failWrite) error("write failed")
            value =
                if (night) value.copy(nightImage = path.orEmpty())
                else value.copy(dayImage = path.orEmpty())
        }

        override suspend fun removeOwnedImage(path: String) {
            call("remove:$path")
        }

        override suspend fun refreshCover() {
            call("cover")
        }
    }

    @Test
    fun millisecondsAreExactIntegersAndClampAtZeroAndEightHundredWithoutScaling() = runBlocking {
        val store = Store()
        val repo = DefaultWelcomeSettingsRepository(store, io)
        repo.milliseconds(501)
        repo.milliseconds(0)
        repo.milliseconds(-1)
        repo.milliseconds(1000)
        assertEquals(listOf("ms:501", "ms:0", "ms:0", "ms:800"), store.calls)
        assertEquals(800, store.value.milliseconds)
    }

    @Test
    fun allFourVisibilitySwitchesRemainIndependentOfImageAndCustomWelcome() = runBlocking {
        val store = Store().apply { value = value.copy(dayImage = "", nightImage = "") }
        val repo = DefaultWelcomeSettingsRepository(store, io)
        repo.boolean(WelcomeSwitch.DayText, false)
        repo.boolean(WelcomeSwitch.NightIcon, false)
        repo.boolean(WelcomeSwitch.Custom, true)
        assertTrue(store.value.switches.getValue(WelcomeSwitch.DayIcon))
        assertTrue(store.value.switches.getValue(WelcomeSwitch.NightText))
        assertFalse(store.value.switches.getValue(WelcomeSwitch.DayText))
        assertFalse(store.value.switches.getValue(WelcomeSwitch.NightIcon))
        assertEquals("", store.value.dayImage)
        assertEquals("", store.value.nightImage)
    }

    @Test
    fun repositoryObservationPreferencesAndImageWorkAllRunOnIoThread() = runBlocking {
        val thread = withContext(io) { Thread.currentThread() }
        val store = Store()
        val repo = DefaultWelcomeSettingsRepository(store, io)
        repo.observe().first()
        repo.boolean(WelcomeSwitch.DayIcon, false)
        repo.image(false, "new")
        repo.image(true, null)
        assertTrue(store.threads.isNotEmpty())
        assertTrue(store.threads.all { it === thread })
    }

    @Test
    fun replacementPublishesCompleteFileBeforeCleaningOldAndDeletionRefreshesCoverWithoutChangingFlags() =
        runBlocking {
            val store = Store()
            val repo = DefaultWelcomeSettingsRepository(store, io)
            repo.image(false, "new-image")
            assertEquals(
                listOf("stage:new-image", "load", "image:false:new-image", "remove:owned-old"),
                store.calls,
            )
            assertEquals("independent-night", store.value.nightImage)
            store.calls.clear()
            repo.image(false, null)
            assertEquals(
                listOf("load", "image:false:null", "remove:new-image", "cover"),
                store.calls,
            )
            assertTrue(store.value.switches.getValue(WelcomeSwitch.DayIcon))
            assertTrue(store.value.switches.getValue(WelcomeSwitch.DayText))
        }

    @Test
    fun incompleteImageAndFailedPreferenceCommitNeverDeleteOriginalAndSamePathDoesNotDeleteItself() =
        runBlocking {
            val store = Store()
            val repo = DefaultWelcomeSettingsRepository(store, io)
            store.failStage = true
            try {
                repo.image(false, "new")
                fail()
            } catch (_: IllegalStateException) {}
            assertEquals("owned-old", store.value.dayImage)
            assertEquals(listOf("stage:new"), store.calls)
            store.failStage = false
            store.failWrite = true
            store.calls.clear()
            try {
                repo.image(false, "new")
                fail()
            } catch (_: IllegalStateException) {}
            assertFalse(store.calls.any { it.startsWith("remove:") })
            assertEquals("owned-old", store.value.dayImage)
            store.failWrite = false
            store.calls.clear()
            repo.image(false, "owned-old")
            assertEquals(listOf("stage:owned-old", "load", "image:false:owned-old"), store.calls)
        }

    @Test
    fun ownershipRequiresCanonicalDirectCoversParentAndRejectsOutsideNestedAndEscapedSymlinks() {
        val root = Files.createTempDirectory("welcome-ownership").toFile()
        try {
            val covers = root.resolve("covers").apply { mkdirs() }
            val own = covers.resolve("owned.png").apply { writeText("owned") }
            val outside = root.resolve("outside.png").apply { writeText("outside") }
            assertTrue(isStoredSettingsImage(root, "covers", own.path))
            assertFalse(isStoredSettingsImage(root, "covers", outside.path))
            assertFalse(
                isStoredSettingsImage(root, "covers", covers.resolve("nested/image.png").path)
            )
            assertFalse(isStoredSettingsImage(root, "covers", null))
            val alias = covers.resolve("alias.png")
            Files.createSymbolicLink(alias.toPath(), outside.toPath())
            assertFalse(isStoredSettingsImage(root, "covers", alias.path))
        } finally {
            root.deleteRecursively()
        }
    }
}
