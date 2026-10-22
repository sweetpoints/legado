package io.legado.app.ui.rss.favorites

import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.RoomRssFavoriteListRepository
import io.legado.app.ui.flutter.FlutterUiTestDriver
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class RssFavoriteListRestoreTest {
    private val driver = FlutterUiTestDriver(InstrumentationRegistry.getInstrumentation())

    @Before fun enableAccessibility() = driver.enableAccessibility()

    @After fun restoreAccessibility() = driver.close()

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
        driver.click("rss-favorites-groups")
        driver.await("favorites group menu action") {
            driver.node("rss-favorites-group-menu-$name")?.let {
                it.recycle()
                true
            }
                ?: scroll(
                        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                    )
                    .let { false }
        }
        driver.click("rss-favorites-group-menu-$name")
    }

    private fun scroll(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        try {
            if (
                root.isVisibleToUser &&
                    root.isScrollable &&
                    root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            )
                return true
            for (i in 0 until root.childCount) if (scroll(root.getChild(i))) return true
            return false
        } finally {
            root.recycle()
        }
    }

    private fun longPress(id: String) {
        driver.awaitIdentifier(id)
        val node = requireNotNull(driver.node(id))
        try {
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        } finally {
            node.recycle()
        }
    }

    private fun scrollTo(group: String, id: String) {
        driver.await("favorites row after real list scrolling") {
            driver.node(id)?.let {
                it.recycle()
                true
            }
                ?: run {
                    scroll(driver.node("rss-favorites-list-$group"))
                    false
                }
        }
    }

    private fun alignRowToTop(group: String, id: String) {
        val list = driver.bounds("rss-favorites-list-$group")
        val row = driver.bounds(id)
        val delta = (row.top - list.top).coerceAtLeast(0)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val started = SystemClock.uptimeMillis()
        val x = list.centerX().toFloat()
        val from = (list.bottom - 16).toFloat()
        fun event(action: Int, y: Float) {
            val motion = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                instrumentation.sendPointerSync(motion)
            } finally {
                motion.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, from)
        for (step in 1..20) {
            event(MotionEvent.ACTION_MOVE, from - (delta + 8) * step / 20f)
            SystemClock.sleep(30)
        }
        SystemClock.sleep(150) // End the real drag at rest instead of introducing fling velocity.
        event(MotionEvent.ACTION_UP, from - delta - 8)
        driver.await("favorites scroll checkpoint") {
            // The checkpoint follows the actual rendered scroll, not a test-only bridge command.
            driver.node(id)?.let { node ->
                node.recycle()
                true
            } ?: false
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun realActivityRecreationKeepsGroupScrollAndSingleFlutterFragment() {
        val a = "$prefix A"
        val b = "$prefix B"
        val rows = add(a, 45)
        add(b)
        ActivityScenario.launch(RssFavoritesActivity::class.java).use { scenario ->
            await(scenario)
            group(a)
            val target = RoomRssFavoriteListRepository.key(rows[20].origin, rows[20].link)
            scrollTo(a, "rss-favorite-row-$target")
            alignRowToTop(a, "rss-favorite-row-$target")
            group(b)
            group(a)
            scenario.recreate()
            await(scenario) { it.loaded && it.group == a }
            val id = RoomRssFavoriteListRepository.key(rows[20].origin, rows[20].link)
            driver.awaitIdentifier("rss-favorite-row-$id")
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
                assertTrue(home.view is android.widget.FrameLayout)
                assertEquals(
                    1,
                    home.childFragmentManager.fragments
                        .filterIsInstance<RssFavoritesFlutterFragment>()
                        .size,
                )
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
            longPress("rss-favorite-row-$id")
            scenario.recreate()
            await(scenario) { it.loaded && it.confirmation != null }
            driver.await("restored confirmation title") {
                driver.text("rss-favorites-confirm-message").contains(original.title)
            }
            driver.click("rss-favorites-cancel-delete")
            runBlocking(Dispatchers.IO) {
                assertEquals(original, appDb.rssStarDao.get(original.origin, original.link))
            }
            longPress("rss-favorite-row-$id")
            driver.click("rss-favorites-confirm-delete")
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
            driver.click("rss-favorites-menu")
            driver.click("rss-favorites-delete-group")
            driver.click("rss-favorites-confirm-delete")
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
