package io.legado.app.ui.book.manage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookSourcePickerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun realtimeSearchAndGroupDisplaySelectExactStableUrl() {
        val repo = Fake()
        val saved = SavedStateHandle()
        lateinit var model: BookSourcePickerViewModel
        var payload: String? = null
        var closes = 0
        compose.runOnIdle { model = BookSourcePickerViewModel(repo, saved) }
        try {
            compose.setContent {
                LegadoComposeTheme {
                    BookSourcePickerRoute(
                        model,
                        { payload = it },
                        { closes++ },
                        // Match the real picker window: a fixed 320dp fixture collapses
                        // the list when its IME padding is applied.
                        Modifier.fillMaxSize(),
                    )
                }
            }
            compose.waitUntil { model.state.value.items.size == 30 }
            compose.onNodeWithTag("source-picker-list").performScrollToIndex(29)
            compose.onNodeWithTag("source-picker-row:29").assertTextEquals("Source 29 (Group)")
            compose.onNodeWithTag("source-picker-search").performTextReplacement("Source 7")
            compose.runOnIdle { assertEquals("Source 7", model.state.value.query) }
            compose.waitUntil { model.state.value.items.size == 1 }
            compose.onNodeWithTag("source-picker-row:29").assertDoesNotExist()
            val row = compose.onNodeWithTag("source-picker-row:7")
            val rowBounds = row.fetchSemanticsNode().boundsInRoot
            val viewport =
                compose.onNodeWithTag("source-picker-list").fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Picker row must have a visible viewport: row=$rowBounds viewport=$viewport",
                viewport.height > 0f && rowBounds.overlaps(viewport),
            )
            row.assertIsDisplayed().performClick()
            try {
                compose.waitUntil { closes > 0 }
            } catch (error: Throwable) {
                throw AssertionError(
                    "Picker delivery: closes=$closes payload=$payload reads=${repo.reads} finished=${model.state.value.finished} busy=${model.state.value.busy} pending=${saved.get<Boolean>("picker.pending")} row=$rowBounds viewport=$viewport",
                    error,
                )
            }
            compose.runOnIdle {
                assertEquals(
                    "Delivery must close once; finished=${model.state.value.finished} pending=${saved.get<Boolean>("picker.pending")}",
                    1,
                    closes,
                )
                assertEquals("full:7", payload)
            }
        } finally {
            compose.runOnIdle { model.viewModelScope.cancel() }
        }
    }

    @Test
    fun numericDraftSurvivesRestorationAndOnlyConfirmWrites() {
        val repo = Fake()
        val saved = SavedStateHandle()
        lateinit var model: BookSourcePickerViewModel
        var active by mutableStateOf<BookSourcePickerViewModel?>(null)
        compose.runOnIdle {
            model = BookSourcePickerViewModel(repo, saved)
            active = model
        }
        try {
            compose.setContent {
                active?.let {
                    LegadoComposeTheme {
                        BookSourcePickerRoute(it, {}, {}, Modifier.height(320.dp))
                    }
                }
            }
            compose.onNodeWithTag("source-picker-menu").performClick()
            compose.onNodeWithTag("source-picker-delay-menu").performClick()
            compose.waitUntil { model.state.value.delayOpen && !model.state.value.delayLoading }
            compose.onNodeWithTag("source-picker-delay").performTextReplacement("10000")
            compose.onNodeWithTag("source-picker-delay-save").assertIsNotEnabled()
            compose.onNodeWithTag("source-picker-delay").performTextReplacement("987")
            compose.runOnIdle {
                active = null
                model.viewModelScope.cancel()
                model =
                    BookSourcePickerViewModel(
                        repo,
                        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
                    )
                active = model
            }
            compose
                .onNodeWithTag("source-picker-delay")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.EditableText,
                        AnnotatedString("987"),
                    )
                )
            compose.onNodeWithTag("source-picker-delay-cancel").performClick()
            assertTrue(repo.writes.isEmpty())
            compose.onNodeWithTag("source-picker-menu").performClick()
            compose.onNodeWithTag("source-picker-delay-menu").performClick()
            compose.waitUntil { !model.state.value.delayLoading }
            compose.onNodeWithTag("source-picker-delay").performTextReplacement("0")
            compose.onNodeWithTag("source-picker-delay-save").performClick()
            compose.waitUntil { repo.writes == listOf(0) }
            compose.onNodeWithTag("source-picker-delay").assertDoesNotExist()
        } finally {
            compose.runOnIdle { model.viewModelScope.cancel() }
        }
    }

    @Test
    fun callbackWaitsForResumeIsConsumedBeforeDeliveryAndDoesNotRepeatAfterRestore() {
        val owner = Owner()
        val repo = Fake()
        val saved = SavedStateHandle()
        lateinit var model: BookSourcePickerViewModel
        var deliveries = 0
        var closes = 0
        var active by mutableStateOf<BookSourcePickerViewModel?>(null)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model = BookSourcePickerViewModel(repo, saved)
            active = model
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    active?.let { current ->
                        LegadoComposeTheme {
                            BookSourcePickerRoute(
                                current,
                                {
                                    assertNull(current.consumeSource())
                                    deliveries++
                                },
                                { closes++ },
                                Modifier.height(320.dp),
                            )
                        }
                    }
                }
            }
            compose.waitUntil { model.state.value.items.isNotEmpty() }
            compose.runOnIdle { model.select("0") }
            compose.waitUntil { model.state.value.finished }
            assertEquals(0, deliveries)
            assertEquals(0, closes)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { closes == 1 }
            assertEquals(1, deliveries)
            compose.runOnIdle {
                active = null
                model.viewModelScope.cancel()
                model =
                    BookSourcePickerViewModel(
                        repo,
                        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
                    )
                active = model
            }
            compose.waitUntil { closes == 2 }
            assertEquals(1, deliveries)
            assertEquals(1, repo.reads)
        } finally {
            compose.runOnIdle { model.viewModelScope.cancel() }
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : BookSourcePickerRepository {
        val rows =
            MutableStateFlow((0..29).map { BookSourcePickerItem("$it", "Source $it", "Group") })
        val writes = mutableListOf<Int>()
        var reads = 0

        override fun observe(query: String) = rows.map {
            it.filter { row -> row.displayName.contains(query) }
        }

        override suspend fun source(url: String): String {
            reads++
            return "full:$url"
        }

        override suspend fun delay(): Int = 15

        override suspend fun saveDelay(value: Int) {
            writes += value
        }
    }
}
