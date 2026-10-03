package io.legado.app.ui.file

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class FileManagementRouteTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<FileManagementViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    @After
    fun after() {
        compose.runOnIdle {
            models.forEach { it.stop() }
            gates.forEach { it.complete(Unit) }
        }
    }

    private fun model(
        store: ManagementDrafts,
        saved: SavedStateHandle = SavedStateHandle(),
    ): FileManagementViewModel {
        lateinit var result: FileManagementViewModel
        compose.runOnIdle {
            result = FileManagementViewModel(ManagementFiles(), store, saved)
            models += result
        }
        return result
    }

    @Test
    fun fileOpenConsumesDurableReceiptBeforeNativeCallbackAndDoesNotRepeatAfterPauseResume() {
        val store = ManagementDrafts()
        val model = model(store)
        val owner = Owner()
        val opened = mutableListOf<String>()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    FileManagementRoute(
                        model,
                        {},
                        {
                            assertNull(store.records.values.single().navigation)
                            opened += it
                        },
                        { error(it) },
                    )
                }
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.onNodeWithTag("file-management-row-/root/root.txt").performClick()
        compose.waitUntil { opened.size == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(listOf("content://provider//root/root.txt"), opened)
    }

    @Test
    fun nonCooperativeClaimCanceledByPauseRollsBackAndResumeDeliversOnlyOnce() {
        val store = ManagementDrafts()
        val model = model(store)
        val owner = Owner()
        val opened = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        gates += gate
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    FileManagementRoute(model, {}, { opened += it }, { error(it) })
                }
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle {
            store.claimGate = gate
            model.click("/root/root.txt")
        }
        compose.waitUntil { store.claimGate == null }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        assertTrue(opened.isEmpty())
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            gate.complete(Unit)
        }
        compose.waitUntil { opened.size == 1 }
        assertNull(model.state.value.navigation)
        assertNull(store.records.values.single().navigation)
    }

    @Test
    fun corruptReceiptWriteShowsSingleErrorAndUserRetryUnblocksExactPendingFileOpen() {
        val store = ManagementDrafts()
        val model = model(store)
        val opened = mutableListOf<String>()
        val errors = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                FileManagementRoute(model, {}, { opened += it }, { errors += it })
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle {
            store.failClaim = true
            model.click("/root/root.txt")
        }
        compose.waitUntil { errors.size == 1 }
        compose.waitForIdle()
        assertEquals(1, errors.size)
        assertTrue(opened.isEmpty())
        assertNotNull(model.state.value.navigation)
        compose.runOnIdle { store.failClaim = false }
        compose.onNodeWithTag("file-management-retry").performClick()
        compose.waitUntil { opened.size == 1 }
        assertEquals(1, errors.size)
    }

    @Test
    fun restoredCompleteNativeUriWaitsForResumedBeforeSingleDelivery() {
        val store = ManagementDrafts()
        val pending = ManagedFileOpen("request", "content://provider/" + "complete".repeat(1000))
        store.records["ticket"] = FileManagementDraft(navigation = pending, revision = 2)
        val saved = SavedStateHandle(mapOf("file.management.ticket" to "ticket"))
        val model = model(store, saved)
        val owner = Owner()
        val opened = mutableListOf<String>()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    FileManagementRoute(model, {}, { opened += it }, { error(it) })
                }
            }
        }
        compose.waitUntil { model.state.value.loaded }
        assertTrue(opened.isEmpty())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { opened.size == 1 }
        assertEquals(pending.uri, opened.single())
    }

    @Test
    fun realBackAtRootCleansDraftAndStopsWithoutRotationCleanup() {
        val store = ManagementDrafts()
        val model = model(store)
        var closes = 0
        compose.setContent {
            LegadoComposeTheme { FileManagementRoute(model, { closes++ }, {}, { error(it) }) }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.onNodeWithTag("file-management-back").performClick()
        compose.waitUntil { closes == 1 && store.released.size == 1 }
        assertTrue(store.records.isEmpty())
        assertFalse(model.state.value.loading)
    }

    @Test
    fun restoredClosedReceiptClosesOnceAndCleansWithoutLoadingOrNativeDelivery() {
        val store = ManagementDrafts()
        store.records["ticket"] =
            FileManagementDraft(navigation = ManagedFileOpen("old", "private uri"))
        val saved =
            SavedStateHandle(
                mapOf("file.management.ticket" to "ticket", "file.management.closed" to true)
            )
        val model = model(store, saved)
        var closes = 0
        compose.setContent {
            LegadoComposeTheme {
                FileManagementRoute(
                    model,
                    { closes++ },
                    { error("closed receipt delivered") },
                    { error(it) },
                )
            }
        }
        compose.waitUntil { closes == 1 && store.records.isEmpty() }
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle
            get() = registry
    }
}
