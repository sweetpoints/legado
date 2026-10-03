package io.legado.app.ui.rss.subscription

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

class RuleSubscriptionRouteTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<RuleSubscriptionViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    @After
    fun after() {
        compose.runOnIdle {
            models.forEach { it.stop() }
            gates.forEach { it.complete(Unit) }
        }
    }

    private fun model(
        rules: SubscriptionRules,
        store: SubscriptionDrafts,
        saved: SavedStateHandle = SavedStateHandle(),
    ): RuleSubscriptionViewModel {
        lateinit var result: RuleSubscriptionViewModel
        compose.runOnIdle {
            result = RuleSubscriptionViewModel(rules, store, saved)
            models += result
        }
        return result
    }

    @Test
    fun eachNativeTypeReceivesExactCompleteUrlOnlyOnceAcrossPauseResume() {
        val rules = SubscriptionRules()
        rules.flow.value =
            rules.flow.value.map { it.copy(url = it.url + "/" + "complete".repeat(1000)) }
        val model = model(rules, SubscriptionDrafts(rules))
        val owner = Owner()
        val opened = mutableListOf<RuleSubscriptionOpen>()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    RuleSubscriptionRoute(model, {}, { opened += it }, { error(it) })
                }
            }
        }
        compose.waitUntil { model.state.value.loaded }
        rules.flow.value.forEachIndexed { index, row ->
            compose.onNodeWithTag("subscription-row-${row.id}").performClick()
            compose.waitUntil { opened.size == index + 1 }
            assertEquals(row.url, opened.last().url)
            assertEquals(row.type, opened.last().type)
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(3, opened.size)
    }

    @Test
    fun canceledNonCooperativeClaimRollsBackAndResumedRouteDeliversExactlyOnce() {
        val rules = SubscriptionRules()
        val store = SubscriptionDrafts(rules)
        val model = model(rules, store)
        val owner = Owner()
        val opened = mutableListOf<RuleSubscriptionOpen>()
        val gate = CompletableDeferred<Unit>()
        gates += gate
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    RuleSubscriptionRoute(model, {}, { opened += it }, { error(it) })
                }
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle {
            store.claimGate = gate
            model.open(1)
        }
        compose.waitUntil { store.claimGate == null }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        assertTrue(opened.isEmpty())
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            gate.complete(Unit)
        }
        compose.waitUntil { opened.size == 1 }
        assertEquals("https://1", opened.single().url)
        assertNull(model.state.value.navigation)
        assertNull(store.records.values.single().navigation)
    }

    @Test
    fun failedClaimShowsOneErrorAndExplicitRetryDeliversWithoutBlockedOrRepeatedActions() {
        val rules = SubscriptionRules()
        val store = SubscriptionDrafts(rules)
        val model = model(rules, store)
        val opened = mutableListOf<RuleSubscriptionOpen>()
        val errors = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                RuleSubscriptionRoute(model, {}, { opened += it }, { errors += it })
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle {
            store.failClaim = true
            model.open(1)
        }
        compose.waitUntil { errors.size == 1 }
        assertTrue(opened.isEmpty())
        assertNotNull(model.state.value.navigation)
        compose.runOnIdle {
            rules.flow.value = rules.flow.value.map { it.copy(name = "Fresh " + it.name) }
        }
        compose.waitForIdle()
        assertEquals(1, errors.size)
        compose.runOnIdle { store.failClaim = false }
        compose.onNodeWithTag("subscription-retry").performClick()
        compose.waitUntil { opened.size == 1 }
        assertNull(model.state.value.navigation)
        assertEquals(1, errors.size)
    }

    @Test
    fun diskRestoredPendingNativeImportIsDeferredUntilResumedAndConsumedBeforeCallback() {
        val rules = SubscriptionRules()
        val store = SubscriptionDrafts(rules)
        val saved = SavedStateHandle(mapOf("rule.subscription.ticket" to "ticket"))
        val pending = RuleSubscriptionOpen("request", 2, "https://restored/full")
        store.records["ticket"] = RuleSubscriptionDraft(navigation = pending, revision = 2)
        val model = model(rules, store, saved)
        val owner = Owner()
        val opened = mutableListOf<RuleSubscriptionOpen>()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    RuleSubscriptionRoute(
                        model,
                        {},
                        {
                            assertNull(store.records["ticket"]!!.navigation)
                            opened += it
                        },
                        { error(it) },
                    )
                }
            }
        }
        compose.waitUntil { model.state.value.loaded }
        assertTrue(opened.isEmpty())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { opened.size == 1 }
        assertEquals(pending, opened.single())
    }

    @Test
    fun realCloseCleansOwnedDraftAndRestoredClosedReceiptClosesWithoutAnyImport() {
        val rules = SubscriptionRules()
        val store = SubscriptionDrafts(rules)
        val saved = SavedStateHandle()
        val model = model(rules, store, saved)
        var closed = 0
        compose.setContent {
            LegadoComposeTheme {
                RuleSubscriptionRoute(
                    model,
                    { closed++ },
                    { error("unexpected import") },
                    { error(it) },
                )
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.onNodeWithTag("subscription-back").performClick()
        compose.waitUntil { closed == 1 && store.released.size == 1 }
        assertTrue(store.records.isEmpty())
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle
            get() = registry
    }
}
