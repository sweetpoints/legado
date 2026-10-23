package io.legado.app.ui.rss.favorites

import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
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
import java.util.concurrent.CountDownLatch
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

    private fun scrollTo(
        group: String,
        id: String,
        scenario: ActivityScenario<RssFavoritesActivity>,
    ) {
        val deadline = SystemClock.uptimeMillis() + 30000
        fun withinBudget(phase: String, condition: () -> Boolean) {
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining > 0) SystemClock.sleep(minOf(25L, remaining))
            }
            throw AssertionError("Favorites scroll navigation timed out: $phase")
        }
        fun position(): FavoriteScrollPosition? {
            var result: FavoriteScrollPosition? = null
            scenario.onActivity { activity ->
                val home = activity.supportFragmentManager
                    .findFragmentByTag(RssFavoritesActivity.HOME) as? RssFavoritesFragment
                home?.let {
                    val state = it.model.state.value
                    if (state.loaded && !state.busy && state.group == group &&
                        state.confirmation == null && it.view?.isAttachedToWindow == true
                    ) result = it.model.scrollPosition(group)
                }
            }
            return result
        }
        fun visibleRows(): List<Pair<String, android.graphics.Rect>> {
            val rows = mutableListOf<Pair<String, android.graphics.Rect>>()
            fun visit(node: AccessibilityNodeInfo?) {
                node ?: return
                try {
                    if (node.isVisibleToUser &&
                        node.viewIdResourceName?.startsWith("rss-favorite-row-") == true
                    ) rows += node.viewIdResourceName to
                        android.graphics.Rect().also(node::getBoundsInScreen)
                    for (index in 0 until node.childCount) visit(node.getChild(index))
                } finally {
                    node.recycle()
                }
            }
            visit(driver.node("rss-favorites-list-$group"))
            return rows
        }
        fun frame() {
            val rendered = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                Choreographer.getInstance().postFrameCallback { rendered.countDown() }
            }
            withinBudget("native frame") { rendered.count == 0L }
        }
        withinBudget("selected group and rendered rows") {
            position() != null && visibleRows().isNotEmpty()
        }
        frame()
        while (SystemClock.uptimeMillis() < deadline) {
            if (driver.node(id)?.let { it.recycle(); true } == true) return
            val before = requireNotNull(position())
            val beforeRows = visibleRows()
            // Accessibility success acknowledges dispatch only. Do not submit the next
            // scroll until this one reaches the native checkpoint and actual semantics.
            assertTrue(scroll(driver.node("rss-favorites-list-$group")))
            withinBudget("native scroll progress and rendered rows") {
                val after = position()
                after != null &&
                    (after.index > before.index ||
                        (after.index == before.index && after.offset > before.offset)) &&
                    visibleRows().let { it.isNotEmpty() && it != beforeRows }
            }
            frame()
        }
        throw AssertionError("Favorites row absent after acknowledged scrolling")
    }

    private fun alignRowToTop(group: String, id: String, scenario: ActivityScenario<RssFavoritesActivity>) {
        val list = driver.bounds("rss-favorites-list-$group")
        val row = driver.bounds(id)
        val delta = (row.top - list.top).coerceAtLeast(0)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val diagnosticStarted = SystemClock.uptimeMillis()
        val x = list.centerX().toFloat()
        val from = (list.bottom - 16).toFloat()
        fun diagnostic(): String {
            var native = "nativeOwnerMissing"
            scenario.onActivity { activity ->
                val home = activity.supportFragmentManager.findFragmentByTag(RssFavoritesActivity.HOME)
                    as? RssFavoritesFragment
                home?.let {
                    val state = it.model.state.value
                    val position = it.model.scrollPosition(group)
                    native = "loaded=${state.loaded},busy=${state.busy},error=${state.error != null}," +
                        "groupMatches=${state.group == group},index=${position.index},offset=${position.offset}," +
                        "confirmation=${state.confirmation?.kind?.name}," +
                        "attached=${it.view?.isAttachedToWindow},windowFocus=${activity.window.decorView.hasWindowFocus()}"
                }
            }
            val node = driver.node(id)
            val rendered = node?.let {
                try {
                    val bounds = android.graphics.Rect().also(it::getBoundsInScreen)
                    "rowPresent=true,rowBounds=$bounds,enabled=${it.isEnabled}"
                } finally { it.recycle() }
            } ?: "rowPresent=false"
            val confirmation = driver.node("rss-favorites-confirm-message")
            val confirmationPresent = confirmation != null
            confirmation?.recycle()
            return "$native,$rendered,confirmationVisible=$confirmationPresent,elapsedMs=${SystemClock.uptimeMillis() - diagnosticStarted}"
        }
        val before = "listBounds=$list,initialRowBounds=$row,delta=$delta,dragDistance=${delta + 8}," +
            "fromY=$from,toY=${from - delta - 8},${diagnostic()}"
        android.util.Log.i("FavoritesScrollTest", "before={$before}")
        val started = SystemClock.uptimeMillis()
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
        try {
            driver.await("favorites scroll checkpoint") {
                // The checkpoint follows the actual rendered scroll, not a test-only bridge command.
                driver.node(id)?.let { node ->
                    node.recycle()
                    true
                } ?: false
            }
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}; before={$before}; after={${diagnostic()}}", failure)
        }
        instrumentation.waitForIdleSync()
        android.util.Log.i("FavoritesScrollTest", "after={${diagnostic()}}")
    }

    @Test
    fun actualSlowDragsAndShortFlingsSwitchGroupsWhileShortRestingDragStaysPut() {
        val a = "$prefix A"
        val b = "$prefix B"
        val first = add(a).single()
        val second = add(b).single()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun frame() {
            val rendered = CountDownLatch(1)
            instrumentation.runOnMainSync {
                Choreographer.getInstance().postFrameCallback { rendered.countDown() }
            }
            driver.await("favorites native frame") { rendered.count == 0L }
            instrumentation.waitForIdleSync()
        }
        ActivityScenario.launch(RssFavoritesActivity::class.java).use { scenario ->
            fun acknowledged(group: String, row: RssStar) {
                await(scenario) { it.loaded && it.group == group }
                val key = RoomRssFavoriteListRepository.key(row.origin, row.link)
                driver.awaitIdentifier("rss-favorite-row-$key")
                frame()
            }
            fun drag(group: String, fraction: Float, duration: Long, rest: Long) {
                frame()
                val list = driver.bounds("rss-favorites-list-$group")
                val startX = list.left + list.width() * if (fraction < 0) .85f else .15f
                val y = list.centerY().toFloat()
                val started = SystemClock.uptimeMillis()
                fun event(action: Int, x: Float) {
                    val motion = MotionEvent.obtain(
                        started, SystemClock.uptimeMillis(), action, x, y, 0
                    )
                    motion.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { instrumentation.sendPointerSync(motion) }
                    finally { motion.recycle() }
                }
                event(MotionEvent.ACTION_DOWN, startX)
                val steps = if (rest > 0) 20 else 8
                for (step in 1..steps) {
                    event(MotionEvent.ACTION_MOVE, startX + list.width() * fraction * step / steps)
                    SystemClock.sleep(duration / steps)
                }
                if (rest > 0) SystemClock.sleep(rest)
                event(MotionEvent.ACTION_UP, startX + list.width() * fraction)
                frame()
            }
            await(scenario)
            group(a)
            acknowledged(a, first)
            // Cross half the actual viewport and stop before release: velocity cannot
            // substitute for the distance threshold under test.
            drag(a, -.7f, 800, 400)
            acknowledged(b, second)
            drag(b, .7f, 800, 400)
            acknowledged(a, first)
            // These short, fast gestures must still navigate without crossing half a page.
            drag(a, -.2f, 80, 0)
            acknowledged(b, second)
            drag(b, .2f, 80, 0)
            acknowledged(a, first)
            drag(a, -.2f, 800, 400)
            val stableUntil = SystemClock.uptimeMillis() + 400
            driver.await("short resting drag retains the current native group") {
                scenario.onActivity { activity ->
                    val home = activity.supportFragmentManager.findFragmentByTag(RssFavoritesActivity.HOME)
                        as RssFavoritesFragment
                    assertEquals(a, home.model.state.value.group)
                }
                SystemClock.uptimeMillis() >= stableUntil
            }
            acknowledged(a, first)
        }
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
            scrollTo(a, "rss-favorite-row-$target", scenario)
            alignRowToTop(a, "rss-favorite-row-$target", scenario)
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
                // Flutter exposes ordinary Text labels as Android contentDescription;
                // node.text is reserved for editable text-field values.
                driver.containsText("rss-favorites-confirm-message", original.title)
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
