package io.legado.app.data.preferences

import io.legado.app.model.cover.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class CoverSettingsRepositoryTest {
    private class Store : CoverSettingsStore {
        var value = CoverSettingsSnapshot()
        val calls = mutableListOf<String>()
        val threads = mutableListOf<Pair<String, Thread>>()
        var failStage = false
        var failWrite = false

        private fun record(name: String) {
            calls += name
            threads += name to Thread.currentThread()
        }

        override fun changes(): Flow<Unit> = flowOf(Unit)

        override suspend fun load(): CoverSettingsSnapshot {
            record("load")
            return value
        }

        override suspend fun boolean(key: CoverSettingSwitch, value: Boolean) {
            record("bool:${key.name}:$value")
            if (failWrite) error("write failed")
            this.value = this.value.copy(switches = this.value.switches + (key to value))
        }

        override suspend fun stageImage(uri: String): String {
            record("stage:$uri")
            if (failStage) error("stage failed")
            return "installed:$uri"
        }

        override suspend fun image(key: CoverSettingImage, path: String?) {
            record("image:${key.name}:$path")
            if (failWrite) error("write failed")
            value = value.copy(images = value.images + (key to path.orEmpty()))
        }

        override suspend fun refreshCover() {
            record("cover")
        }

        override suspend fun refreshBookshelf() {
            record("shelf")
        }
    }

    @Test
    fun authorDependenciesAreIndependentAndDisabledWritesDoNotResetStoredAuthor() = runBlocking {
        val store = Store()
        val repo =
            DefaultCoverSettingsRepository(store, Dispatchers.Unconfined, Dispatchers.Unconfined)
        repo.boolean(CoverSettingSwitch.DayName, false)
        assertFalse(store.value.enabled(CoverSettingSwitch.DayAuthor))
        assertTrue(store.value.enabled(CoverSettingSwitch.NightAuthor))
        store.calls.clear()
        repo.boolean(CoverSettingSwitch.DayAuthor, false)
        assertEquals(listOf("load"), store.calls)
        assertTrue(store.value.switches.getValue(CoverSettingSwitch.DayAuthor))
        repo.boolean(CoverSettingSwitch.DayName, true)
        repo.boolean(CoverSettingSwitch.DayAuthor, false)
        assertFalse(store.value.switches.getValue(CoverSettingSwitch.DayAuthor))
        assertTrue(store.value.switches.getValue(CoverSettingSwitch.NightAuthor))
    }

    @Test
    fun wifiChangesOnlyPreferenceWhileDrawingChangesRefreshCoverThenShelfOnTheirOwnedDispatchers() =
        runBlocking {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
                    val ioThread = withContext(io) { Thread.currentThread() }
                    val mainThread = withContext(main) { Thread.currentThread() }
                    val store = Store()
                    val repo = DefaultCoverSettingsRepository(store, io, main)
                    repo.boolean(CoverSettingSwitch.Wifi, true)
                    assertEquals(listOf("load", "bool:Wifi:true"), store.calls)
                    store.calls.clear()
                    store.threads.clear()
                    repo.boolean(CoverSettingSwitch.Default, true)
                    assertEquals(listOf("load", "bool:Default:true", "cover", "shelf"), store.calls)
                    store.threads.forEach { (action, thread) ->
                        assertSame(if (action == "shelf") mainThread else ioThread, thread)
                    }
                    store.threads.clear()
                    repo.observe().first()
                    assertSame(ioThread, store.threads.single().second)
                }
            }
        }

    @Test
    fun fourImageTargetsPublishOnlyAfterPreparationAndRemovalKeepsAllOtherTargets() = runBlocking {
        val store = Store()
        val repo =
            DefaultCoverSettingsRepository(store, Dispatchers.Unconfined, Dispatchers.Unconfined)
        CoverSettingImage.entries.forEach { key ->
            store.calls.clear()
            repo.image(key, key.name)
            assertEquals(
                listOf("stage:${key.name}", "image:${key.name}:installed:${key.name}", "cover"),
                store.calls,
            )
        }
        val before = store.value.images
        store.calls.clear()
        repo.image(CoverSettingImage.RecordNight, null)
        assertEquals(listOf("image:RecordNight:null", "cover"), store.calls)
        assertEquals(
            before - CoverSettingImage.RecordNight,
            store.value.images - CoverSettingImage.RecordNight,
        )
        assertEquals("", store.value.images.getValue(CoverSettingImage.RecordNight))
    }

    @Test
    fun imagePreparationAndPreferenceFailuresNeverRefreshOrPublishBlankOverExistingImage() =
        runBlocking {
            val store = Store()
            val repo =
                DefaultCoverSettingsRepository(
                    store,
                    Dispatchers.Unconfined,
                    Dispatchers.Unconfined,
                )
            store.value =
                store.value.copy(images = store.value.images + (CoverSettingImage.Day to "old"))
            store.failStage = true
            assertTrue(runCatching { repo.image(CoverSettingImage.Day, "new") }.isFailure)
            assertEquals(listOf("stage:new"), store.calls)
            assertEquals("old", store.value.images.getValue(CoverSettingImage.Day))
            store.failStage = false
            store.failWrite = true
            store.calls.clear()
            assertTrue(runCatching { repo.image(CoverSettingImage.Day, "new") }.isFailure)
            assertEquals(listOf("stage:new", "image:Day:installed:new"), store.calls)
            assertEquals("old", store.value.images.getValue(CoverSettingImage.Day))
        }
}
