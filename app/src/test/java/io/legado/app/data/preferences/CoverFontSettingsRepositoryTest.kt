package io.legado.app.data.preferences

import io.legado.app.model.cover.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class CoverFontSettingsRepositoryTest {
    private class Store : CoverFontSettingsStore {
        var value = CoverFontSettingsSnapshot()
        val calls = mutableListOf<String>()
        val threads = mutableListOf<Pair<String, Thread>>()
        var invalid = false
        var failWrite = false

        private fun call(name: String) {
            calls += name
            threads += name to Thread.currentThread()
        }

        override fun changes(): Flow<Unit> = flowOf(Unit)

        override suspend fun load(): CoverFontSettingsSnapshot {
            call("load")
            return value
        }

        override suspend fun boolean(key: CoverFontSwitch, value: Boolean) {
            call("bool:${key.name}:$value")
            if (failWrite) error("write failed")
            this.value = this.value.copy(switches = this.value.switches + (key to value))
        }

        override suspend fun size(key: CoverFontSize, value: Int) {
            call("size:${key.name}:$value")
            this.value = this.value.copy(sizes = this.value.sizes + (key to value))
        }

        override suspend fun stageFont(path: String): String {
            call("stage:$path")
            if (invalid) error("invalid font")
            return "installed:$path"
        }

        override suspend fun font(path: String) {
            call("font:$path")
            if (failWrite) error("write failed")
            value = value.copy(fontPath = path)
        }

        override suspend fun refreshCover() {
            call("cover")
        }

        override suspend fun refreshPreviewAndBookshelf() {
            call("preview-shelf")
        }
    }

    @Test
    fun sizesRemainStoredWhileDisabledAndEnabledEditsClampPercentRangeWithoutChangingOtherSizes() =
        runBlocking {
            val store = Store()
            val repo =
                DefaultCoverFontSettingsRepository(
                    store,
                    Dispatchers.Unconfined,
                    Dispatchers.Unconfined,
                )
            repo.size(CoverFontSize.TitleLarge, 123)
            assertEquals(listOf("load"), store.calls)
            assertEquals(100, store.value.sizes.getValue(CoverFontSize.TitleLarge))
            repo.boolean(CoverFontSwitch.CustomSize, true)
            repo.size(CoverFontSize.TitleLarge, 999)
            repo.size(CoverFontSize.AuthorSmall, 0)
            assertEquals(200, store.value.sizes.getValue(CoverFontSize.TitleLarge))
            assertEquals(50, store.value.sizes.getValue(CoverFontSize.AuthorSmall))
            assertEquals(100, store.value.sizes.getValue(CoverFontSize.TitleSmall))
            repo.size(CoverFontSize.TitleLarge, 100)
            repo.boolean(CoverFontSwitch.CustomSize, false)
            assertEquals(50, store.value.sizes.getValue(CoverFontSize.AuthorSmall))
        }

    @Test
    fun allStyleEditsRefreshOnIoThenPublishPreviewAndBookshelfOnMain() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
                val ioThread = withContext(io) { Thread.currentThread() }
                val mainThread = withContext(main) { Thread.currentThread() }
                val store = Store()
                val repo = DefaultCoverFontSettingsRepository(store, io, main)
                repo.boolean(CoverFontSwitch.Horizontal, true)
                assertEquals(listOf("bool:Horizontal:true", "cover", "preview-shelf"), store.calls)
                store.threads.forEach { (action, thread) ->
                    assertSame(if (action == "preview-shelf") mainThread else ioThread, thread)
                }
                store.threads.clear()
                repo.observe().first()
                assertSame(ioThread, store.threads.single().second)
            }
        }
    }

    @Test
    fun fontIsInstalledAndValidatedBeforePublishingAndDefaultResetsOnlyFont() = runBlocking {
        val store = Store()
        val repo =
            DefaultCoverFontSettingsRepository(
                store,
                Dispatchers.Unconfined,
                Dispatchers.Unconfined,
            )
        repo.font("source.ttf")
        assertEquals(
            listOf("stage:source.ttf", "font:installed:source.ttf", "cover", "preview-shelf"),
            store.calls,
        )
        store.calls.clear()
        repo.font("")
        assertEquals(listOf("font:", "cover", "preview-shelf"), store.calls)
        assertEquals("", store.value.fontPath)
        assertEquals(CoverFontSwitch.entries.associateWith { it.default }, store.value.switches)
    }

    @Test
    fun invalidFontOrFailedPreferenceWriteNeverRefreshesOrOverwritesExistingFont() = runBlocking {
        val store =
            Store().apply {
                value = value.copy(fontPath = "previous.ttf")
                invalid = true
            }
        val repo =
            DefaultCoverFontSettingsRepository(
                store,
                Dispatchers.Unconfined,
                Dispatchers.Unconfined,
            )
        assertTrue(runCatching { repo.font("broken.ttf") }.isFailure)
        assertEquals(listOf("stage:broken.ttf"), store.calls)
        store.calls.clear()
        store.invalid = false
        store.failWrite = true
        assertTrue(runCatching { repo.font("valid.ttf") }.isFailure)
        assertEquals(listOf("stage:valid.ttf", "font:installed:valid.ttf"), store.calls)
        assertEquals("previous.ttf", store.value.fontPath)
    }
}
