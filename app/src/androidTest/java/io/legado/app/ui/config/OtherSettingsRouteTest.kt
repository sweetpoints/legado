package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.preferences.*
import io.legado.app.model.settings.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class OtherSettingsRouteTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Repository : OtherSettingsRepository {
        val value = MutableStateFlow(OtherSettingsSnapshot())
        var writes = 0

        override fun observe(): Flow<OtherSettingsSnapshot> = value

        override suspend fun load() = value.value

        override suspend fun readText(key: OtherText) = ""

        override suspend fun boolean(key: OtherSwitch, value: Boolean): List<OtherEffect> {
            writes++
            this.value.value =
                this.value.value.copy(switches = this.value.value.switches + (key to value))
            return emptyList()
        }

        override suspend fun number(key: OtherNumber, value: Int) = emptyList<OtherEffect>()

        override suspend fun text(key: OtherText, value: String) = emptyList<OtherEffect>()

        override suspend fun choice(key: OtherChoice, value: String) = emptyList<OtherEffect>()
    }

    private class Drafts : OtherSettingsDraftRepository {
        var value = OtherSettingsDraft()

        override suspend fun open(session: String) = value

        override suspend fun write(session: String, draft: OtherSettingsDraft) {
            if (draft.revision >= value.revision) value = draft
        }

        override suspend fun release(session: String) {}
    }

    @Test
    fun pausedEffectsWaitForResumedAndConsumeBeforeHostWithoutRepeatingAcrossCompositionRecreation() {
        lateinit var owner: Owner
        lateinit var model: OtherSettingsViewModel
        val repository = Repository()
        val store = ViewModelStore()
        val received = mutableListOf<OtherEffect>()
        val picker = mutableListOf<String>()
        var attached by mutableStateOf(true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
            model = OtherSettingsViewModel(repository, Drafts(), SavedStateHandle())
            store.put("other", model)
        }
        try {
            compose.setContent {
                if (attached)
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        LegadoComposeTheme {
                            OtherSettingsRoute(
                                model,
                                { true },
                                { receipt ->
                                    assertFalse(model.consumeEffect(receipt.id))
                                    received += receipt.effect
                                },
                                { picker += it },
                                {},
                            )
                        }
                    }
            }
            compose.waitUntil(timeoutMillis = 10000) { !model.state.value.loading }
            compose.runOnIdle { model.boolean(OtherSwitch.TokenRequired, false) }
            compose.waitUntil(timeoutMillis = 10000) {
                model.state.value.draft?.effects?.size == 2 && !model.state.value.busy
            }
            assertTrue(received.isEmpty())
            assertEquals(1, repository.writes)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(timeoutMillis = 10000) { received.size == 2 }
            assertEquals(listOf(OtherEffect.RestartWeb, OtherEffect.RestartMcp), received)
            compose.runOnIdle {
                owner.registry.currentState = Lifecycle.State.STARTED
                attached = false
            }
            compose.waitForIdle()
            compose.runOnIdle {
                attached = true
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
            compose.waitForIdle()
            assertEquals(2, received.size)
            compose.runOnIdle {
                owner.registry.currentState = Lifecycle.State.STARTED
                model.pickBookTree()
            }
            compose.waitForIdle()
            assertTrue(picker.isEmpty())
            val ticket = model.bookTreeTicket()!!
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(timeoutMillis = 10000) { picker.size == 1 }
            assertEquals(listOf(ticket), picker)
            compose.runOnIdle { attached = false }
            compose.waitForIdle()
            compose.runOnIdle { attached = true }
            compose.waitForIdle()
            assertEquals(1, picker.size)
            compose.runOnIdle { model.pickedBookTree(null, ticket) }
            assertNull(model.bookTreeTicket())
            assertEquals(1, repository.writes)
        } finally {
            compose.runOnIdle {
                attached = false
                owner.registry.currentState = Lifecycle.State.DESTROYED
                model.stop()
                store.clear()
            }
        }
    }
}
