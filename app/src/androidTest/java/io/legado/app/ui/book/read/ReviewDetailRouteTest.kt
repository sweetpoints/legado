package io.legado.app.ui.book.read

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.widget.dialog.photo.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReviewDetailRouteTest {
    @get:Rule val compose = createComposeRule()
    private val key = ReviewDetailKey(1, 0, "token", "book", "source", 1)
    private val loader = PhotoImageLoader {
        PhotoImage.Static(Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888))
    }

    @Test
    fun photoWaitsForResumeAndIsConsumedBeforeHostPausesAndRecreatesComposition() {
        val owner = Owner()
        lateinit var model: ReviewDetailViewModel
        var photos = 0
        var visible by mutableStateOf(true)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model = ReviewDetailViewModel(Fake(), SavedStateHandle(), key)
        }
        compose.setContent {
            if (visible)
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    LegadoComposeTheme {
                        val audio = remember {
                            ReviewDetailAudio({ error("audio unused") }, { error(it) })
                        }
                        ReviewDetailRoute(
                            model,
                            2,
                            "source",
                            loader,
                            audio,
                            { true },
                            { url ->
                                assertEquals("image", url)
                                assertTrue(model.state.value.effects.isEmpty())
                                photos++
                                owner.registry.currentState = Lifecycle.State.CREATED
                            },
                            { error(it) },
                            {},
                            {},
                        )
                    }
                }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.runOnIdle { model.photo(model.state.value.rows.first().key) }
        compose.waitForIdle()
        assertEquals(0, photos)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { photos == 1 }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            visible = true
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, photos)
    }

    @Test
    fun pausedListRetainsItsVisibleContentUntilResumeAndCloseCancelsWork() {
        val owner = Owner()
        val repo = Fake()
        lateinit var model: ReviewDetailViewModel
        var closes = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            model = ReviewDetailViewModel(repo, SavedStateHandle(), key)
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    val audio = remember { ReviewDetailAudio({ error("audio unused") }, {}) }
                    ReviewDetailRoute(
                        model,
                        2,
                        "source",
                        loader,
                        audio,
                        { true },
                        {},
                        {},
                        {},
                        { closes++ },
                    )
                }
            }
        }
        compose.onNodeWithText("first").assertExists()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model.nextPage()
        }
        compose.waitUntil { model.state.value.snapshot.items.size == 2 }
        compose.onNodeWithText("second").assertDoesNotExist()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("second").assertExists()
        compose.runOnIdle { model.cancel() }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, closes)
        assertTrue(model.state.value.finished)
    }

    @Test
    fun restoredPhotoWaitsForDiskCacheInsteadOfConsumingAnUnresolvedRow() {
        val repo = Fake()
        lateinit var original: ReviewDetailViewModel
        val saved = SavedStateHandle()
        compose.runOnIdle { original = ReviewDetailViewModel(repo, saved, key) }
        compose.waitUntil { !original.state.value.loading }
        lateinit var restored: ReviewDetailViewModel
        var photos = 0
        compose.runOnIdle {
            original.photo(original.state.value.rows.first().key)
            repo.restoreGate = CompletableDeferred()
            restored =
                ReviewDetailViewModel(
                    repo,
                    SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
                    key,
                )
        }
        compose.setContent {
            LegadoComposeTheme {
                val audio = remember { ReviewDetailAudio({ error("audio unused") }, {}) }
                ReviewDetailRoute(
                    restored,
                    2,
                    "source",
                    loader,
                    audio,
                    { true },
                    {
                        assertEquals("image", it)
                        photos++
                    },
                    {},
                    {},
                    {},
                )
            }
        }
        compose.waitForIdle()
        assertEquals(0, photos)
        assertEquals(1, restored.state.value.effects.size)
        compose.runOnIdle { repo.restoreGate!!.complete(Unit) }
        compose.waitUntil { photos == 1 }
        assertTrue(restored.state.value.effects.isEmpty())
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : ReviewDetailRepository {
        var restoreGate: CompletableDeferred<Unit>? = null
        var cached: ReviewDetailSnapshot? = null

        override suspend fun detail(key: ReviewDetailKey, page: Int, nextUrl: String?) =
            ReviewDetailPage(
                listOf(
                    ReviewComment(
                        "$page",
                        null,
                        "name",
                        null,
                        emptyList(),
                        if (page == 1) "first" else "second",
                        "image",
                        null,
                        null,
                        null,
                        null,
                        emptyList(),
                    )
                ),
                "next",
                true,
                false,
            )

        override suspend fun replies(
            key: ReviewDetailKey,
            reviewId: String,
            page: Int,
        ): ReviewReplyPage? = null

        override suspend fun mediaItem(key: ReviewDetailKey, url: String): MediaItem? = null

        override suspend fun restore(session: String, key: ReviewDetailKey): ReviewDetailSnapshot? {
            restoreGate?.await()
            return cached
        }

        override suspend fun stage(session: String, snapshot: ReviewDetailSnapshot) {
            cached = snapshot
        }
    }
}
