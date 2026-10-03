package io.legado.app.ui.book.read

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextActionMenuComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun moreAndBackShowConfiguredPartitionsAndDismissResetReturnsPrimary() {
        lateinit var model: TextActionMenuViewModel
        compose.runOnIdle {
            model = TextActionMenuViewModel(Fake())
            model.refresh()
        }
        compose.setContent { LegadoComposeTheme { TextActionMenuRoute(model, { true }, {}) } }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("text-action-primary").assertExists()
        compose.onNodeWithTag("text-action-secondary").assertDoesNotExist()
        compose.onNodeWithTag("text-action-more").performClick()
        compose.onNodeWithTag("text-action-primary").assertDoesNotExist()
        compose.onNodeWithTag("text-action-secondary").assertExists()
        compose.onNodeWithTag("text-action-more").performClick()
        compose.onNodeWithTag("text-action-primary").assertExists()
        compose.runOnIdle {
            model.toggleMore()
            model.reset()
        }
        compose.onNodeWithTag("text-action-primary").assertExists()
    }

    @Test
    fun realLongPressOnItemOnlyChangesSpeakModeAndMoreLongPressOnlyOpensEditor() {
        lateinit var model: TextActionMenuViewModel
        val repo = Fake()
        val events = mutableListOf<TextActionEvent>()
        compose.runOnIdle {
            model = TextActionMenuViewModel(repo)
            model.refresh()
        }
        compose.setContent {
            LegadoComposeTheme { TextActionMenuRoute(model, { true }, { events += it }) }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("text-action-primary").performTouchInput { longClick() }
        compose.waitUntil { events.size == 1 }
        assertEquals(1, repo.mode)
        assertEquals(TextActionEventKind.Toast, events.single().kind)
        compose.onNodeWithTag("text-action-more").performTouchInput { longClick() }
        compose.waitUntil { events.size == 2 }
        assertEquals(TextActionEventKind.Edit, events.last().kind)
        assertTrue(events.none { it.kind == TextActionEventKind.Invoke })
        assertFalse(model.state.value.more)
    }

    @Test
    fun actionWaitsForResumeAndIsConsumedBeforeHostCanPauseWithoutReplay() {
        val owner = Owner()
        lateinit var model: TextActionMenuViewModel
        var calls = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model = TextActionMenuViewModel(Fake())
            model.refresh()
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    TextActionMenuRoute(
                        model,
                        { true },
                        { event ->
                            assertEquals(TextActionEventKind.Invoke, event.kind)
                            assertEquals("primary", event.action!!.id)
                            assertTrue(model.state.value.events.isEmpty())
                            calls++
                            owner.registry.currentState = Lifecycle.State.CREATED
                        },
                    )
                }
            }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.runOnIdle { model.invoke("primary") }
        assertEquals(0, calls)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { calls == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, calls)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : TextActionRepository {
        var mode = 0

        override suspend fun load() =
            TextActionSnapshot(
                listOf(TextAction("primary", TextActionKind.Copy, "Copy")),
                listOf(TextAction("secondary", TextActionKind.Share, "Share")),
            )

        override suspend fun toggleSpeakMode(): Int {
            mode = if (mode == 0) 1 else 0
            return mode
        }
    }
}
