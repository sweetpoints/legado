package io.legado.app.ui.association

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AddBookLinkRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun navigationWaitsForResumeAndIsConsumedBeforePlatformLaunchAndClose() {
        val owner = Owner()
        lateinit var model: AddBookLinkViewModel
        var opens = 0
        var closes = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model = AddBookLinkViewModel(Fake(), SavedStateHandle(), "url")
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    AddBookLinkRoute(
                        model,
                        { true },
                        { target ->
                            assertNull(model.state.value.target)
                            assertTrue(model.state.value.finished)
                            assertEquals("Name", target.name)
                            opens++
                            // Starting BookInfo can pause the import host immediately. Closing must
                            // not wait for another emission.
                            owner.registry.currentState = Lifecycle.State.CREATED
                        },
                        { error("unexpected failure") },
                        {
                            assertEquals(1, opens)
                            closes++
                        },
                    )
                }
            }
        }
        compose.waitUntil { model.state.value.target != null }
        assertEquals(0, opens)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, opens)
    }

    @Test
    fun errorIsDeliveredOnlyOnceAcrossCompositionRecreationAndClosesAfterToast() {
        lateinit var model: AddBookLinkViewModel
        var visible by mutableStateOf(true)
        var errors = 0
        var closes = 0
        compose.runOnIdle { model = AddBookLinkViewModel(Fake(true), SavedStateHandle(), "url") }
        compose.setContent {
            if (visible)
                LegadoComposeTheme {
                    AddBookLinkRoute(
                        model,
                        { true },
                        { error("unexpected navigation") },
                        { message ->
                            assertEquals("missing source", message)
                            assertNull(model.state.value.error)
                            errors++
                        },
                        {
                            assertEquals(1, errors)
                            closes++
                        },
                    )
                }
        }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        assertEquals(1, errors)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake(private val fail: Boolean = false) : AddBookLinkRepository {
        override suspend fun resolve(session: String, url: String): BookLinkTarget {
            if (fail) error("missing source")
            return BookLinkTarget("Name", "Author", url)
        }
    }
}
