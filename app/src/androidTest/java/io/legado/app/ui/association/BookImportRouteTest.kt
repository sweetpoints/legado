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

class BookImportRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun hostEffectsWaitForResumeAndAreConsumedBeforeDelivery() {
        val owner = Owner()
        lateinit var model: BookImportViewModel
        var deliveries = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model = model(Fake())
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    BookImportRoute(
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
        lateinit var model: BookImportViewModel
        var visible by mutableStateOf(true)
        var deliveries = 0
        var closes = 0
        compose.runOnIdle { model = model(repo) }
        compose.setContent {
            if (visible)
                LegadoComposeTheme {
                    BookImportRoute(
                        model,
                        { true },
                        { deliveries++ },
                        {},
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

    @Test
    fun finishedRestorationDeliversReaderJsonOnceOnlyAfterResumeAndClosesOnce() {
        val owner = Owner()
        val repo = Fake()
        var deliveries = 0
        var closes = 0
        lateinit var model: BookImportViewModel
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model =
                BookImportViewModel(
                    repo,
                    Requests(),
                    SavedStateHandle(mapOf("finished" to true, "readerPending" to true)),
                    "source",
                    BookImportSearchLabels(
                        "enabled",
                        "disabled",
                        "login",
                        "no group",
                        "enabled explore",
                        "disabled explore",
                    ),
                    "https://a",
                )
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    BookImportRoute(
                        model,
                        { true },
                        {},
                        {},
                        {
                            assertEquals("published:https://a", it)
                            deliveries++
                        },
                        { closes++ },
                        Modifier.height(500.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, deliveries)
        assertEquals(0, closes)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, deliveries)
        assertEquals(1, closes)
        assertEquals(0, repo.inserts)
    }

    private fun model(repo: Fake) =
        BookImportViewModel(
            repo,
            Requests(),
            SavedStateHandle(),
            "source",
            BookImportSearchLabels(
                "enabled",
                "disabled",
                "login",
                "no group",
                "enabled explore",
                "disabled explore",
            ),
        )

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Requests : BookImportRequestRepository {
        override suspend fun write(request: BookImportRefreshRequest) = error("unused")

        override suspend fun read(id: String): BookImportRefreshRequest? = null

        override suspend fun remove(id: String) = Unit
    }

    private class Fake : BookImportRepository {
        var cache: BookImportSnapshot? = null
        var inserts = 0

        override suspend fun load(source: String) = listOf(BookImportOriginal("a", "{}"))

        override suspend fun refresh(
            originals: List<BookImportOriginal>,
            automatic: Boolean,
            manualIds: Map<String, List<Long>>,
        ) =
            listOf(
                BookImportEntry(
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
                    true,
                    false,
                    BookImportStatus.New,
                    true,
                    true,
                )
            )

        override suspend fun parseEdited(key: String, code: String) = error("unused")

        override suspend fun source(url: String): String? = "published:$url"

        override suspend fun groups() = emptyList<String>()

        override suspend fun preferences() = BookImportPreferences()

        override suspend fun preferences(value: BookImportPreferences) = Unit

        override suspend fun restore(session: String) = cache

        override suspend fun stage(session: String, snapshot: BookImportSnapshot) {
            cache = snapshot
        }

        override suspend fun insert(
            session: String,
            snapshot: BookImportSnapshot,
            selected: Set<String>,
            preferences: BookImportPreferences,
            group: String?,
            addGroup: Boolean,
        ) {
            inserts++
        }
    }
}
