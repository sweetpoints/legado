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

class ImportDictRuleRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun codeRequestWaitsForResumeAndIsConsumedBeforeOpeningHost() {
        val owner = Owner()
        lateinit var model: ImportDictRuleViewModel
        var deliveries = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model = ImportDictRuleViewModel(Fake(), SavedStateHandle(), "input")
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ImportDictRuleRoute(
                        model,
                        { true },
                        {
                            assertNull(model.state.value.code)
                            deliveries++
                        },
                        {},
                        Modifier.height(500.dp),
                    )
                }
            }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.runOnIdle { model.openCode("a") }
        compose.waitForIdle()
        assertEquals(0, deliveries)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { deliveries == 1 }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, deliveries)
    }

    @Test
    fun rebuildingCompositionDoesNotLaunchCodeTwiceAndConfirmClosesAfterInsert() {
        val repo = Fake()
        lateinit var model: ImportDictRuleViewModel
        var visible by mutableStateOf(true)
        var deliveries = 0
        var closes = 0
        compose.runOnIdle { model = ImportDictRuleViewModel(repo, SavedStateHandle(), "input") }
        compose.setContent {
            if (visible)
                LegadoComposeTheme {
                    ImportDictRuleRoute(
                        model,
                        { true },
                        { deliveries++ },
                        {
                            assertEquals(1, repo.inserts)
                            closes++
                        },
                        Modifier.height(500.dp),
                    )
                }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.runOnIdle { model.openCode("a") }
        compose.waitUntil { deliveries == 1 }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        assertEquals(1, deliveries)
        compose.runOnIdle { model.confirm() }
        compose.waitUntil { closes == 1 }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : DictRuleImportRepository {
        var cache: DictRuleImportSession? = null
        var inserts = 0

        override suspend fun read(source: String) =
            listOf(DictRuleImportItem("a", "Rule", "{}", false))

        override suspend fun edit(key: String, code: String) = error("unused")

        override suspend fun restore(session: String) = cache

        override suspend fun stage(session: String, items: List<DictRuleImportItem>) {
            cache = DictRuleImportSession(items)
        }

        override suspend fun insert(
            session: String,
            items: List<DictRuleImportItem>,
            selected: Set<String>,
        ) {
            inserts++
        }
    }
}
