package io.legado.app.ui.login

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.model.login.LoginUiV2
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SourceLoginFormRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pausedHostRetainsUrlUntilResumeThenDoesNotReplay() {
        val owner = Owner()
        val store = ViewModelStore()
        lateinit var model: SourceLoginFormViewModel
        val delivered = mutableListOf<SourceLoginFormAction>()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model = SourceLoginFormViewModel(Fake(), SavedStateHandle())
            store.put("form", model)
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    SourceLoginFormRoute(
                        model,
                        { true },
                        {
                            assertTrue(
                                model.state.value.pending.none { event -> event.id == it.id }
                            )
                            delivered += it.action
                        },
                        {},
                        Modifier.height(600.dp),
                    )
                }
            }
        }
        try {
            compose.waitUntil { model.state.value.rendered }
            compose.runOnIdle { model.action(model.state.value.rows.single(), false) }
            compose.waitForIdle()
            assertTrue(delivered.isEmpty())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { delivered.size == 1 }
            compose.runOnIdle {
                owner.registry.currentState = Lifecycle.State.CREATED
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
            compose.waitForIdle()
            assertEquals(listOf(SourceLoginFormAction.OpenUrl), delivered)
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    @Test
    fun compositionRecreationKeepsDeliveredLogConsumed() {
        val store = ViewModelStore()
        lateinit var model: SourceLoginFormViewModel
        var visible by mutableStateOf(true)
        var logs = 0
        compose.runOnIdle {
            model = SourceLoginFormViewModel(Fake(), SavedStateHandle())
            store.put("form", model)
        }
        compose.setContent {
            if (visible)
                LegadoComposeTheme {
                    SourceLoginFormRoute(model, { true }, { logs++ }, {}, Modifier.height(600.dp))
                }
        }
        try {
            compose.runOnIdle { model.log() }
            compose.waitUntil { logs == 1 }
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            compose.runOnIdle { visible = true }
            compose.waitForIdle()
            assertEquals(1, logs)
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : SourceLoginFormRepository {
        override val definition = SourceLoginDefinition("source", false, emptyMap())

        override suspend fun render(values: Map<String, String>, stateJson: String) =
            SourceLoginRendered(
                listOf(SourceLoginRow("Open", "button", action = "https://example.com"))
            )

        override suspend fun label(script: String, values: Map<String, String>): String? = null

        override suspend fun legacyAction(
            script: String,
            values: Map<String, String>,
            long: Boolean,
            java: Any,
        ) = Unit

        override suspend fun legacyLogin(values: Map<String, String>, java: Any) = true

        override suspend fun action(
            action: String,
            stateJson: String,
            values: Map<String, String>,
        ) = LoginUiV2.ActionResult()

        override suspend fun store(json: String) = true

        override suspend fun persist(values: Map<String, String>) = Unit

        override suspend fun header(): String? = null

        override suspend fun deleteHeader() = Unit

        override suspend fun clear() = Unit
    }
}
