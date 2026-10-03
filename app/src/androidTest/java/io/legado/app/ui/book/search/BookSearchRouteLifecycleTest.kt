package io.legado.app.ui.book.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.preferences.BookSearchPreferencesRepository
import io.legado.app.data.repository.BookSearchDraftRepository
import io.legado.app.data.repository.BookSearchMetadataRepository
import io.legado.app.help.book.ReadRecordIndex
import io.legado.app.model.webBook.BookSearchDraft
import io.legado.app.model.webBook.BookSearchHistory
import io.legado.app.model.webBook.BookSearchMembership
import io.legado.app.model.webBook.BookSearchPreferences
import io.legado.app.model.webBook.BookSearchReceipt
import io.legado.app.model.webBook.BookSearchSuggestion
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookSearchRouteLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pausedPreparationAndLifecycleRecreationDoNotRepeatNativeDelivery() {
        lateinit var model: BookSearchViewModel
        lateinit var lifecycle: Owner
        val saved = SavedStateHandle()
        val drafts = Drafts()
        val delivered = mutableListOf<BookSearchReceipt>()
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = BookSearchViewModel(drafts, Preferences(), Metadata(), saved)
            lifecycle = Owner().apply { registry.currentState = Lifecycle.State.CREATED }
        }
        val currentOwner = mutableStateOf<LifecycleOwner>(lifecycle)
        compose.setContent {
            LegadoComposeTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides currentOwner.value) {
                    BookSearchRoute(
                        model = model,
                        available = { true },
                        prepare = { receipt ->
                            attempts++
                            gate.await()
                            PreparedBookSearchEffect(receipt)
                        },
                        handle = { delivered += it.receipt },
                        abandon = {},
                        close = {},
                    )
                }
            }
        }
        try {
            compose.waitUntil(timeoutMillis = 10000) { model.state.value.ready }
            compose.runOnIdle { model.openLog() }
            compose.waitUntil(timeoutMillis = 10000) {
                model.state.value.durableRevision == model.state.value.draft.revision
            }
            assertTrue(delivered.isEmpty())
            compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(timeoutMillis = 10000) { attempts == 1 }
            compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.STARTED }
            compose.runOnIdle { gate.complete(Unit) }
            assertTrue(delivered.isEmpty())
            compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(timeoutMillis = 10000) { delivered.size == 1 }
            compose.runOnIdle {
                lifecycle.registry.currentState = Lifecycle.State.DESTROYED
                lifecycle = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
                currentOwner.value = lifecycle
            }
            compose.waitForIdle()
            assertEquals(1, delivered.size)
            assertTrue(model.state.value.draft.effects.isEmpty())
        } finally {
            compose.runOnIdle {
                model.stop()
                lifecycle.registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Drafts : BookSearchDraftRepository {
        private var value = BookSearchDraft()

        override suspend fun open(session: String) = value

        override suspend fun write(session: String, draft: BookSearchDraft) {
            value = draft
        }

        override suspend fun release(session: String) = Unit
    }

    private class Preferences : BookSearchPreferencesRepository {
        private val value = MutableStateFlow(BookSearchPreferences())

        override fun observe() = value

        override suspend fun load() = value.value

        override suspend fun precision(value: Boolean) = this.value.value.copy(precision = value)

        override suspend fun showReadRecord(value: Boolean) =
            this.value.value.copy(showReadRecord = value)

        override suspend fun resultFilter(value: String) =
            this.value.value.copy(resultFilter = value)

        override suspend fun scope(value: String) = this.value.value.copy(scope = value)
    }

    private class Metadata : BookSearchMetadataRepository {
        override fun history(query: String) = flowOf(emptyList<BookSearchHistory>())

        override fun suggestions(query: String) = flowOf(emptyList<BookSearchSuggestion>())

        override fun membership() = flowOf(BookSearchMembership(emptySet(), ReadRecordIndex.EMPTY))

        override fun groups() = flowOf(emptyList<String>())

        override suspend fun hasNamedBook(name: String) = false

        override suspend fun saveHistory(word: String) = Unit

        override suspend fun deleteHistory(word: String) = Unit

        override suspend fun clearHistory() = Unit
    }
}
