package io.legado.app.ui.rss.favorites

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.RssStar
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class RssFavoriteListScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: RssFavoriteListViewModel

    private class Fake : RssFavoriteListRepository {
        val snapshots = MutableStateFlow(RssFavoriteListSnapshot(emptyList(), emptyList()))
        var stars = listOf<RssStar>()
        val deletes = mutableListOf<String>()
        var readGate: CompletableDeferred<Unit>? = null
        var deleteGate: CompletableDeferred<Unit>? = null
        var resolves = 0
        var uncooperative = false

        fun emit(vararg values: RssStar) {
            stars = values.toList()
            snapshots.value =
                RssFavoriteListSnapshot(
                    stars.map { it.group }.distinct().sorted(),
                    stars.map(RoomRssFavoriteListRepository::row),
                )
        }

        override fun observe() = snapshots

        override suspend fun resolve(id: String): RssStar? {
            resolves++
            if (uncooperative) withContext(NonCancellable) { readGate?.await() }
            else readGate?.await()
            return stars
                .firstOrNull { RoomRssFavoriteListRepository.key(it.origin, it.link) == id }
                ?.copy()
        }

        override suspend fun delete(id: String) {
            deletes += "row:$id"
            deleteGate?.await()
        }

        override suspend fun deleteGroup(group: String) {
            deletes += "group:$group"
            deleteGate?.await()
        }

        override suspend fun deleteAll() {
            deletes += "all"
            deleteGate?.await()
        }
    }

    private class Images : RssFavoriteImageRepository {
        val requests = mutableListOf<Pair<String, String>>()
        var clears = 0

        override suspend fun load(
            source: String,
            origin: String,
            width: Int,
            height: Int,
        ): AnimatedDrawableResource? {
            requests += source to origin
            return if (source == "failed") null
            else AnimatedDrawableResource(ColorDrawable(Color.RED)) { clears++ }
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private fun star(group: String, link: String = group, image: String? = null) =
        RssStar(
            origin = "origin",
            link = link,
            title = "Title $link",
            pubDate = "Today",
            group = group,
            image = image,
        )

    private fun show(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        read: (RssStar) -> Unit = {},
        owner: Owner? = null,
        images: Images = Images(),
    ) {
        compose.runOnIdle { model = RssFavoriteListViewModel(repo, saved) }
        compose.setContent {
            LegadoComposeTheme {
                if (owner == null) RssFavoriteListRoute(model, true, { true }, read, {}, images)
                else
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        RssFavoriteListRoute(model, true, { true }, read, {}, images)
                    }
            }
        }
        compose.waitUntil { model.state.value.loaded }
    }

    @After
    fun cleanup() {
        if (::model.isInitialized) compose.runOnIdle { model.stop() }
    }

    @Test
    fun groupedTabsMenuAndHorizontalSwipeKeepActualVisibleGroup() {
        val repo = Fake().apply { emit(star("A"), star("B")) }
        show(repo)
        compose.onNodeWithTag("rss-favorites-tab-B").performClick()
        compose.waitUntil { model.state.value.group == "B" }
        compose.onNodeWithTag("rss-favorites-list-B").assertIsDisplayed()
        compose.onNodeWithTag("rss-favorites-pager").performTouchInput { swipeRight() }
        compose.waitUntil { model.state.value.group == "A" }
        compose.onNodeWithTag("rss-favorites-groups").performClick()
        compose.onNodeWithTag("rss-favorites-group-menu-B").performClick()
        compose.waitUntil { model.state.value.group == "B" }
        compose.onNodeWithTag("rss-favorites-tab-B").assertIsSelected()
    }

    @Test
    fun singleGroupHidesTabsAndReadResolvesLatestFullArticle() {
        val repo = Fake().apply { emit(star("A")) }
        val reads = mutableListOf<RssStar>()
        show(repo, read = { reads += it })
        compose.onNodeWithTag("rss-favorites-tabs").assertDoesNotExist()
        val id = model.state.value.visibleRows.single().id
        compose.runOnIdle {
            repo.stars =
                listOf(
                    repo.stars
                        .single()
                        .copy(
                            title = "Latest",
                            type = 2,
                            content = "Full body",
                            variable = "{data}",
                        )
                )
        }
        compose.onNodeWithTag("rss-favorite-row-$id").performClick()
        compose.waitUntil { reads.size == 1 }
        assertEquals("Latest", reads.single().title)
        assertEquals(2, reads.single().type)
        assertEquals("Full body", reads.single().content)
        assertNull(model.state.value.pendingRead)
    }

    @Test
    fun longPressShowsExactDeletionConfirmationAndCancelDoesNotWrite() {
        val repo = Fake().apply { emit(star("A")) }
        show(repo)
        val id = model.state.value.visibleRows.single().id
        compose.onNodeWithTag("rss-favorite-row-$id").performTouchInput { longClick() }
        compose.onNodeWithTag("rss-favorites-confirm-message").assertTextContains("Title A")
        compose.onNodeWithTag("rss-favorites-cancel-delete").performClick()
        assertTrue(repo.deletes.isEmpty())
        compose.onNodeWithTag("rss-favorite-row-$id").performTouchInput { longClick() }
        compose.onNodeWithTag("rss-favorites-confirm-delete").performClick()
        compose.waitUntil { repo.deletes.size == 1 }
        assertEquals("row:$id", repo.deletes.single())
    }

    @Test
    fun toolbarGroupAndAllDeleteKeepCapturedTargetAndBlockDuplicates() {
        val repo =
            Fake().apply {
                emit(star("A"), star("B"))
                deleteGate = CompletableDeferred()
            }
        show(repo)
        compose.onNodeWithTag("rss-favorites-menu").performClick()
        compose.onNodeWithTag("rss-favorites-delete-group").performClick()
        compose.runOnIdle { model.selectGroup("B") }
        compose.onNodeWithTag("rss-favorites-confirm-delete").performClick()
        compose.onNodeWithTag("rss-favorites-confirm-delete").assertIsNotEnabled()
        compose.onNodeWithTag("rss-favorites-cancel-delete").assertIsNotEnabled()
        compose.runOnIdle {
            model.confirmDelete()
            assertEquals(listOf("group:A"), repo.deletes)
            repo.deleteGate!!.complete(Unit)
        }
        compose.waitUntil { !model.state.value.busy }
        compose.onNodeWithTag("rss-favorites-menu").performClick()
        compose.onNodeWithTag("rss-favorites-delete-all").performClick()
        compose.onNodeWithTag("rss-favorites-confirm-delete").performClick()
        compose.waitUntil { repo.deletes.size == 2 }
        assertEquals("all", repo.deletes.last())
    }

    @Test
    fun perGroupScrollRestoresOnlyAfterRowsArriveAndKeepsIndependentPositions() {
        val repo = Fake()
        val saved = SavedStateHandle()
        show(repo, saved)
        compose.runOnIdle {
            repo.emit(*(0..45).map { star("A", "a$it") }.plus(star("B")).toTypedArray())
        }
        compose.onNodeWithTag("rss-favorites-list-A").performScrollToIndex(20)
        compose.waitUntil { model.scrollPosition("A").index >= 19 }
        compose.onNodeWithTag("rss-favorites-tab-B").performClick()
        compose.waitUntil { model.state.value.group == "B" }
        compose.onNodeWithTag("rss-favorites-tab-A").performClick()
        compose.waitUntil { model.state.value.group == "A" }
        val id = RoomRssFavoriteListRepository.key("origin", "a20")
        compose.onNodeWithTag("rss-favorite-row-$id").assertIsDisplayed()
        assertEquals(0, model.scrollPosition("B").index)
    }

    @Test
    fun imagesRetainOriginAndDisappearAfterBlankOrFailedImageWithoutReusingOldDrawable() {
        val repo = Fake().apply { emit(star("A", image = "loaded")) }
        val images = Images()
        show(repo, images = images)
        val id = model.state.value.visibleRows.single().id
        compose.onNodeWithTag("rss-favorite-image-$id", useUnmergedTree = true).assertExists()
        assertEquals(listOf("loaded" to "origin"), images.requests)
        compose.runOnIdle { repo.emit(star("A", image = "")) }
        compose.onNodeWithTag("rss-favorite-image-$id", useUnmergedTree = true).assertDoesNotExist()
        compose.waitUntil { images.clears == 1 }
        compose.runOnIdle { repo.emit(star("A", image = "failed")) }
        compose.onNodeWithTag("rss-favorite-image-$id", useUnmergedTree = true).assertDoesNotExist()
        compose.waitUntil { images.requests.size == 2 }
        assertEquals("failed" to "origin", images.requests.last())
    }

    @Test
    fun pausingDuringNonCooperativeResolveKeepsTicketForResumedSingleNavigation() {
        val owner = Owner()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val repo =
            Fake().apply {
                emit(star("A"))
                readGate = CompletableDeferred()
                uncooperative = true
            }
        var reads = 0
        show(repo, read = { reads++ }, owner = owner)
        val id = model.state.value.visibleRows.single().id
        compose.onNodeWithTag("rss-favorite-row-$id").performClick()
        compose.waitUntil { repo.resolves == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.runOnIdle { repo.readGate!!.complete(Unit) }
        compose.waitForIdle()
        assertEquals(0, reads)
        assertEquals(id, model.state.value.pendingRead)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { reads == 1 }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, reads)
    }

    @Test
    fun throwingNativeNavigationConsumesTicketAndAllowsNextClick() {
        val repo = Fake().apply { emit(star("A")) }
        var reads = 0
        show(
            repo,
            read = {
                reads++
                error("native")
            },
        )
        val id = model.state.value.visibleRows.single().id
        compose.onNodeWithTag("rss-favorite-row-$id").performClick()
        compose.waitUntil { reads == 1 }
        assertNull(model.state.value.pendingRead)
        compose.onNodeWithTag("rss-favorite-row-$id").performClick()
        compose.waitUntil { reads == 2 }
        assertNull(model.state.value.pendingRead)
    }
}
