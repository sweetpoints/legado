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

class RssImportRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun hostEffectsWaitForResumeAndAreConsumedBeforeDelivery() {
        val owner = Owner()
        lateinit var model: RssImportViewModel
        var deliveries = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model = model(Fake())
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    RssImportRoute(
                        model,
                        { true },
                        {
                            assertTrue(
                                model.state.value.effects.none { effect -> effect.id == it.id }
                            )
                            deliveries++
                        },
                        {},
                        {},
                        Modifier.height(500.dp),
                    )
                }
            }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.runOnIdle { model.code("a") }
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
    fun compositionRebuildDoesNotRepeatPlatformCallbackAndCloseFollowsInsert() {
        val repo = Fake()
        lateinit var model: RssImportViewModel
        var visible by mutableStateOf(true)
        var deliveries = 0
        var closes = 0
        compose.runOnIdle { model = model(repo) }
        compose.setContent {
            if (visible)
                LegadoComposeTheme {
                    RssImportRoute(
                        model,
                        { true },
                        { deliveries++ },
                        {},
                        {
                            assertEquals(1, repo.inserts)
                            closes++
                        },
                        Modifier.height(500.dp),
                    )
                }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.runOnIdle { model.code("a") }
        compose.waitUntil { deliveries == 1 }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        assertEquals(1, deliveries)
        compose.runOnIdle { model.confirm() }
        compose.waitUntil { closes == 1 }
    }

    private fun model(repo: Fake) =
        RssImportViewModel(
            repo,
            Requests(),
            SavedStateHandle(),
            "source",
            RssImportSearchLabels("enabled", "disabled", "login", "no group"),
        )

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Requests : RssImportRequestRepository {
        override suspend fun write(request: RssImportRefreshRequest) = error("unused")

        override suspend fun read(id: String): RssImportRefreshRequest? = null

        override suspend fun remove(id: String) = Unit
    }

    private class Fake : RssImportRepository {
        var cache: RssImportSnapshot? = null
        var inserts = 0

        override suspend fun load(source: String) = listOf(RssImportOriginal("a", "{}"))

        override suspend fun refresh(
            originals: List<RssImportOriginal>,
            automatic: Boolean,
            manualIds: Map<String, List<Long>>,
        ) =
            listOf(
                RssImportEntry(
                    "a",
                    "{}",
                    "{}",
                    null,
                    null,
                    emptyList(),
                    null,
                    "Rule",
                    "https://a",
                    null,
                    null,
                    true,
                    null,
                    RssImportStatus.New,
                    true,
                    true,
                )
            )

        override suspend fun parseEdited(key: String, code: String) = error("unused")

        override suspend fun groups() = emptyList<String>()

        override suspend fun preferences() = RssImportPreferences()

        override suspend fun preferences(value: RssImportPreferences) = Unit

        override suspend fun restore(session: String) = cache

        override suspend fun stage(session: String, snapshot: RssImportSnapshot) {
            cache = snapshot
        }

        override suspend fun insert(
            session: String,
            snapshot: RssImportSnapshot,
            selected: Set<String>,
            preferences: RssImportPreferences,
            group: String?,
            addGroup: Boolean,
        ) {
            inserts++
        }
    }
}
