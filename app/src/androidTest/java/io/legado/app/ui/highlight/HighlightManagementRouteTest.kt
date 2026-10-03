package io.legado.app.ui.highlight

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

class HighlightManagementRouteTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<HighlightManagementViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    @After
    fun after() {
        compose.runOnIdle {
            models.forEach { it.stop() }
            gates.forEach { it.complete(Unit) }
        }
    }

    private fun model(
        store: ManagedHighlightSessions = ManagedHighlightSessions(),
        saved: SavedStateHandle = SavedStateHandle(),
    ): HighlightManagementViewModel {
        lateinit var model: HighlightManagementViewModel
        compose.runOnIdle {
            model = HighlightManagementViewModel(saved, ManagedHighlights(), store)
            models += model
        }
        return model
    }

    @Test
    fun nativeEditorConsumedDurablyBeforeCallbackAndPauseResumeCannotRepeat() {
        val store = ManagedHighlightSessions()
        val model = model(store)
        val owner = Owner()
        val delivered = mutableListOf<HighlightManagementEffect>()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    HighlightManagementRoute(
                        model,
                        ManagedHighlightTransfers(),
                        {},
                        { effect, _, _ ->
                            assertTrue(store.records.values.single().effects.isEmpty())
                            delivered += effect
                        },
                        { error(it) },
                    )
                }
            }
        }
        compose.waitUntil { model.state.value.rules.size == 3 }
        compose.onNodeWithTag("highlight-management-edit-a").performClick()
        compose.waitUntil { delivered.size == 1 }
        assertEquals(HighlightManagementAction.Edit, delivered.single().action)
        assertEquals(1L, delivered.single().id)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, delivered.size)
    }

    @Test
    fun nonCooperativeClaimCanceledByPauseRestoresReceiptThenResumesOnce() {
        val store = ManagedHighlightSessions()
        val model = model(store)
        val owner = Owner()
        var delivered = 0
        val gate = CompletableDeferred<Unit>()
        gates += gate
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    HighlightManagementRoute(
                        model,
                        ManagedHighlightTransfers(),
                        {},
                        { _, _, _ -> delivered++ },
                        { error(it) },
                    )
                }
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle {
            store.gate = gate
            model.action(HighlightManagementAction.Add)
        }
        compose.waitUntil { store.claimStarted }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        assertEquals(0, delivered)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            gate.complete(Unit)
        }
        compose.waitUntil { delivered == 1 }
        assertTrue(model.state.value.draft.effects.isEmpty())
    }

    @Test
    fun nonCooperativeExportPayloadDuringPauseResumeCannotDeliverFromCanceledCollector() {
        val store = ManagedHighlightSessions()
        val model = model(store)
        val owner = Owner()
        val transfer = ManagedHighlightTransfers()
        val gate = CompletableDeferred<Unit>()
        gates += gate
        transfer.gate = gate
        var delivered = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    HighlightManagementRoute(
                        model,
                        transfer,
                        {},
                        { effect, bytes, _ ->
                            assertEquals(HighlightManagementAction.Export, effect.action)
                            assertNotNull(bytes)
                            delivered++
                        },
                        { error(it) },
                    )
                }
            }
        }
        compose.waitUntil { model.state.value.rules.size == 3 }
        compose.runOnIdle { model.export(true) }
        compose.waitUntil { transfer.started }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        assertEquals(0, delivered)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            transfer.gate = null
            gate.complete(Unit)
        }
        compose.waitUntil { delivered == 1 }
        assertNotNull(model.state.value.draft.exporting)
        assertTrue(model.state.value.draft.effects.isEmpty())
    }

    @Test
    fun invalidPayloadIsConsumedWithSingleErrorAndDoesNotBlockNextNativeAction() {
        val model = model()
        val transfer = ManagedHighlightTransfers()
        transfer.fail = true
        val errors = mutableListOf<String>()
        val delivered = mutableListOf<HighlightManagementEffect>()
        compose.setContent {
            LegadoComposeTheme {
                HighlightManagementRoute(
                    model,
                    transfer,
                    {},
                    { effect, _, _ -> delivered += effect },
                    { errors += it },
                )
            }
        }
        compose.waitUntil { model.state.value.rules.size == 3 }
        compose.runOnIdle { model.export(true) }
        compose.waitUntil {
            errors.size == 1 &&
                model.state.value.draft.exporting == null &&
                model.state.value.draft.effects.isEmpty()
        }
        compose.runOnIdle {
            model.select("a")
            model.action(HighlightManagementAction.Add)
        }
        compose.waitUntil { delivered.size == 1 }
        assertEquals(HighlightManagementAction.Add, delivered.single().action)
        assertEquals(1, errors.size)
    }

    @Test
    fun restoredRefreshWaitsForResumedAndOnlyThenCallsReaderOnce() {
        val store = ManagedHighlightSessions()
        val effect = HighlightManagementEffect("refresh", HighlightManagementAction.Refresh)
        store.records["ticket"] = HighlightManagementDraft(effects = listOf(effect), revision = 1)
        val model =
            model(store, SavedStateHandle(mapOf(HighlightManagementViewModel.KEY to "ticket")))
        val owner = Owner()
        var refreshes = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    HighlightManagementRoute(
                        model,
                        ManagedHighlightTransfers(),
                        {},
                        { _, _, _ -> refreshes++ },
                        { error(it) },
                    )
                }
            }
        }
        compose.waitUntil { model.state.value.loaded }
        assertEquals(0, refreshes)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { refreshes == 1 }
        assertTrue(store.records["ticket"]!!.effects.isEmpty())
    }

    @Test
    fun closedRestorationOnlyClosesAndReleasesWithoutNativeNavigation() {
        val store = ManagedHighlightSessions()
        store.records["ticket"] =
            HighlightManagementDraft(
                effects = listOf(HighlightManagementEffect("old", HighlightManagementAction.Add))
            )
        val model =
            model(
                store,
                SavedStateHandle(
                    mapOf(
                        HighlightManagementViewModel.KEY to "ticket",
                        "highlight.management.closed" to true,
                    )
                ),
            )
        var closes = 0
        compose.setContent {
            LegadoComposeTheme {
                HighlightManagementRoute(
                    model,
                    ManagedHighlightTransfers(),
                    { closes++ },
                    { _, _, _ -> error("Closed navigation") },
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
