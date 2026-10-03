package io.legado.app.ui.rss.favorites

import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ActivityScenario
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.RoomRssFavoriteListRepository
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class RssFavoriteListRestoreTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inserted = mutableListOf<RssStar>()
    private val prefix = "Compose favorites ${UUID.randomUUID()}"

    @After
    fun cleanup() =
        runBlocking(Dispatchers.IO) {
            inserted.forEach { appDb.rssStarDao.delete(it.origin, it.link) }
        }

    private fun add(group: String, count: Int = 1): List<RssStar> {
        val values =
            (0 until count).map {
                RssStar(
                    origin = prefix,
                    link = "https://example.invalid/$group/$it",
                    group = group,
                    title = "Title $it",
                    starTime = 1000L - it,
                )
            }
        inserted += values
        runBlocking(Dispatchers.IO) { appDb.rssStarDao.insert(*values.toTypedArray()) }
        return values
    }

    private fun await(
        scenario: ActivityScenario<RssFavoritesActivity>,
        predicate: (RssFavoriteListState) -> Boolean = { it.loaded },
    ) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            scenario.onActivity {
                val fragment =
                    it.supportFragmentManager.findFragmentByTag(RssFavoritesActivity.HOME)
                        as? RssFavoritesFragment
                ready = fragment?.model?.state?.value?.let(predicate) == true
            }
            if (ready) return
            SystemClock.sleep(25)
        }
        throw AssertionError("Favorites state did not load/restore")
    }

    private fun group(name: String) {
        compose.onNodeWithTag("rss-favorites-groups").performClick()
        compose.onNodeWithTag("rss-favorites-group-menu-$name").performScrollTo().performClick()
    }

    @Test
    fun realActivityRecreationKeepsGroupScrollAndSingleComposeFragment() {
        val a = "$prefix A"
        val b = "$prefix B"
        val rows = add(a, 45)
        add(b)
        ActivityScenario.launch(RssFavoritesActivity::class.java).use { scenario ->
            await(scenario)
            group(a)
            compose.onNodeWithTag("rss-favorites-list-$a").performScrollToIndex(20)
            group(b)
            group(a)
            scenario.recreate()
            await(scenario) { it.loaded && it.group == a }
            val id = RoomRssFavoriteListRepository.key(rows[20].origin, rows[20].link)
            compose.onNodeWithTag("rss-favorite-row-$id").assertIsDisplayed()
            scenario.onActivity { activity ->
                assertEquals(
                    1,
                    activity.supportFragmentManager.fragments
                        .filterIsInstance<RssFavoritesFragment>()
                        .size,
                )
                val home =
                    activity.supportFragmentManager.findFragmentByTag(RssFavoritesActivity.HOME)
                        as RssFavoritesFragment
                assertTrue(home.view is androidx.compose.ui.platform.ComposeView)
                assertTrue(home.model.scrollPosition(a).index >= 19)
                assertEquals(0, home.model.scrollPosition(b).index)
            }
        }
    }

    @Test
    fun restoredRowConfirmationCanCancelWithoutRoomWriteThenDeleteExactCompositeKey() {
        val a = "$prefix A"
        val original = add(a).single()
        val other = original.copy(origin = "$prefix-other")
        inserted += other
        runBlocking(Dispatchers.IO) { appDb.rssStarDao.insert(other) }
        ActivityScenario.launch(RssFavoritesActivity::class.java).use { scenario ->
            await(scenario)
            group(a)
            val id = RoomRssFavoriteListRepository.key(original.origin, original.link)
            compose.onNodeWithTag("rss-favorite-row-$id").performTouchInput { longClick() }
            scenario.recreate()
            await(scenario) { it.loaded && it.confirmation != null }
            compose
                .onNodeWithTag("rss-favorites-confirm-message")
                .assertTextContains(original.title)
            compose.onNodeWithTag("rss-favorites-cancel-delete").performClick()
            runBlocking(Dispatchers.IO) {
                assertEquals(original, appDb.rssStarDao.get(original.origin, original.link))
            }
            compose.onNodeWithTag("rss-favorite-row-$id").performTouchInput { longClick() }
            compose.onNodeWithTag("rss-favorites-confirm-delete").performClick()
            await(scenario) { it.loaded && it.rows.none { row -> row.id == id } }
            runBlocking(Dispatchers.IO) {
                assertNull(appDb.rssStarDao.get(original.origin, original.link))
                assertEquals(other, appDb.rssStarDao.get(other.origin, other.link))
            }
        }
    }

    @Test
    fun exactGroupDeletionUpdatesRealTabsAndRetainsAnotherGroupAcrossResume() {
        val a = "$prefix A"
        val b = "$prefix B"
        val removed = add(a)
        val retained = add(b)
        ActivityScenario.launch(RssFavoritesActivity::class.java).use { scenario ->
            await(scenario)
            group(a)
            compose.onNodeWithTag("rss-favorites-menu").performClick()
            compose.onNodeWithTag("rss-favorites-delete-group").performClick()
            compose.onNodeWithTag("rss-favorites-confirm-delete").performClick()
            await(scenario) { it.loaded && a !in it.groups }
            group(b)
            scenario.recreate()
            await(scenario) { it.loaded && it.group == b }
            runBlocking(Dispatchers.IO) {
                assertTrue(removed.all { appDb.rssStarDao.get(it.origin, it.link) == null })
                assertTrue(retained.all { appDb.rssStarDao.get(it.origin, it.link) == it })
            }
        }
    }

    @Test
    fun legacyPagerFragmentsAreRemovedWithoutDiscardingRestoredDialogs() {
        add("$prefix A")
        ActivityScenario.launch(RssFavoritesActivity::class.java).use { scenario ->
            await(scenario)
            scenario.onActivity { activity ->
                activity.supportFragmentManager
                    .beginTransaction()
                    .add(RssFavoritesFragment("legacy"), "legacy-pager-page")
                    .commitNow()
                FavoritesRestorationSentinelDialog()
                    .showNow(activity.supportFragmentManager, "favorites-sentinel")
            }
            scenario.recreate()
            await(scenario)
            scenario.onActivity { activity ->
                assertNull(activity.supportFragmentManager.findFragmentByTag("legacy-pager-page"))
                assertEquals(
                    1,
                    activity.supportFragmentManager.fragments
                        .filterIsInstance<RssFavoritesFragment>()
                        .size,
                )
                assertNotNull(
                    activity.supportFragmentManager.findFragmentByTag("favorites-sentinel")
                )
            }
        }
    }
}

class FavoritesRestorationSentinelDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext())
            .setMessage("Restored dialog")
            .setPositiveButton("Close", null)
            .create()
}
