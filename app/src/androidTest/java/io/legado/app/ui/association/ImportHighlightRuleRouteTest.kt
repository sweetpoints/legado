package io.legado.app.ui.association

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
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

class ImportHighlightRuleRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun successfulImportWaitsForResumeThenConsumesReaderRefreshBeforeClosing() {
        val owner = Owner()
        val repo = Fake()
        lateinit var model: ImportHighlightRuleViewModel
        var refreshes = 0
        var closes = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model = ImportHighlightRuleViewModel(repo, SavedStateHandle(), "uri")
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ImportHighlightRuleRoute(
                        model,
                        { true },
                        {
                            assertFalse(model.state.value.refreshPending)
                            refreshes++
                        },
                        {
                            assertEquals(1, refreshes)
                            closes++
                        },
                        Modifier.height(500.dp),
                    )
                }
            }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.runOnIdle { model.confirm() }
        compose.waitUntil { model.state.value.finished }
        assertEquals(0, refreshes)
        assertEquals(0, closes)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { closes == 1 }
        assertEquals(1, refreshes)
        assertEquals(1, repo.inserts)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, refreshes)
        assertEquals(1, repo.inserts)
    }

    @Test
    fun rebuildingCompositionCannotRedeliverReaderRefreshAndCancelDoesNotRefresh() {
        val repo = Fake()
        lateinit var model: ImportHighlightRuleViewModel
        var visible by mutableStateOf(true)
        var refreshes = 0
        var closes = 0
        compose.runOnIdle { model = ImportHighlightRuleViewModel(repo, SavedStateHandle(), "uri") }
        compose.setContent {
            if (visible)
                LegadoComposeTheme {
                    ImportHighlightRuleRoute(
                        model,
                        { true },
                        { refreshes++ },
                        { closes++ },
                        Modifier.height(500.dp),
                    )
                }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.runOnIdle { model.confirm() }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        assertEquals(1, refreshes)
        assertEquals(1, repo.inserts)
        val beforeCancel = closes
        compose.runOnIdle {
            visible = false
            model = ImportHighlightRuleViewModel(Fake(), SavedStateHandle(), "uri")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            visible = true
            model.cancel()
        }
        compose.waitUntil { closes == beforeCancel + 1 }
        assertEquals(1, refreshes)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : HighlightImportRepository {
        var cache: HighlightImportSession? = null
        var inserts = 0

        override suspend fun read(source: String) =
            listOf(HighlightImportItem("a", "Rule", "{}", HighlightImportStatus.NEW))

        override suspend fun restore(session: String) = cache

        override suspend fun stage(session: String, items: List<HighlightImportItem>) {
            cache = HighlightImportSession(items)
        }

        override suspend fun insert(
            session: String,
            items: List<HighlightImportItem>,
            selected: Set<String>,
        ) {
            inserts++
            cache = HighlightImportSession(items, true)
        }
    }
}
