package io.legado.app.ui.about

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.image.CoverImage
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ReadingHistoryRouteTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<ReadingHistoryViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val identity = ReadingHistoryIdentity("Title", "Author")
    private val white = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val covers =
        object : ReadingHistoryCoverRepository {
            override suspend fun load(
                cover: ReadingHistoryCover,
                fallback: String?,
                width: Int,
                height: Int,
            ) = ReadingHistoryCoverResult(CoverImage.Static(white), true)
        }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle
            get() = registry
    }

    private inner class Repo : ReadingHistoryRepository {
        private val row =
            ReadingHistoryRow(
                identity,
                "Author",
                listOf("Author"),
                false,
                3000,
                1,
                "Chapter",
                ReadingHistoryCover(null, null, null, false),
            )

        override suspend fun load(query: String, sort: Int) =
            ReadingHistorySnapshot(listOf(row), 1, 3000, listOf(row))

        override suspend fun preferences() = ReadingHistoryPreferences()

        override suspend fun preferences(
            value: ReadingHistoryPreferences,
            fields: Set<ReadingHistoryPreference>,
        ) = Unit

        override suspend fun clear() = Unit

        override suspend fun delete(identity: ReadingHistoryIdentity) = Unit

        override suspend fun removeAuthor(identity: ReadingHistoryIdentity, author: String) = Unit

        override suspend fun destination(identity: ReadingHistoryIdentity) =
            ReadingHistoryDestination(ReadingHistoryReader.Text, "book-url", "Title")
    }

    private class Drafts : ReadingHistoryDraftRepository {
        val values = mutableMapOf<String, ReadingHistoryDraft>()
        var gate: CompletableDeferred<Unit>? = null
        var claiming = false

        override suspend fun create() =
            UUID.randomUUID().toString().also { values[it] = ReadingHistoryDraft() }

        override suspend fun read(ticket: String) = checkNotNull(values[ticket])

        override suspend fun write(ticket: String, draft: ReadingHistoryDraft) {
            if (values[ticket]?.navigation != null && draft.navigation == null) {
                claiming = true
                gate?.await()
            }
            if (draft.revision > (values[ticket]?.revision ?: -1)) values[ticket] = draft
        }

        override suspend fun release(ticket: String) {
            values.remove(ticket)
        }
    }

    private fun model(drafts: Drafts): ReadingHistoryViewModel {
        lateinit var result: ReadingHistoryViewModel
        compose.runOnIdle {
            result = ReadingHistoryViewModel(Repo(), drafts, SavedStateHandle())
            models += result
        }
        return result
    }

    @After
    fun after() {
        compose.runOnIdle {
            gates.forEach { it.complete(Unit) }
            models.forEach {
                it.stop()
                it.viewModelScope.cancel()
            }
        }
    }

    @Test
    fun actualLifecyclePauseWhileDiskClaimIsGatedKeepsRequestAndResumeDeliversExactlyOnce() {
        val drafts = Drafts()
        val gate = CompletableDeferred<Unit>()
        gates += gate
        val vm = model(drafts)
        val owner = Owner()
        var launches = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ReadingHistoryRoute(vm, covers, {}, { launches++ }, { error(it) })
                }
            }
        }
        compose.waitUntil { vm.state.value.ready && !vm.state.value.loading }
        compose.runOnIdle {
            drafts.gate = gate
            vm.open(identity)
        }
        compose.waitUntil { drafts.claiming }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            gate.complete(Unit)
        }
        compose.waitUntil { drafts.values.values.single().navigation != null }
        assertEquals(0, launches)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { launches == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, launches)
        assertNull(vm.state.value.navigation)
    }

    @Test
    fun failedNativeReaderLaunchIsConsumedAndRefreshNeverRepeatsIt() {
        val drafts = Drafts()
        val vm = model(drafts)
        var launches = 0
        var failures = 0
        compose.setContent {
            LegadoComposeTheme {
                ReadingHistoryRoute(
                    vm,
                    covers,
                    {},
                    {
                        launches++
                        error("Reader missing")
                    },
                    { failures++ },
                )
            }
        }
        compose.waitUntil { vm.state.value.ready && !vm.state.value.loading }
        compose.runOnIdle { vm.open(identity) }
        compose.waitUntil { failures == 1 }
        compose.runOnIdle { vm.refresh() }
        compose.waitUntil { !vm.state.value.loading }
        assertEquals(1, launches)
        assertNull(drafts.values.values.single().navigation)
    }
}
