package io.legado.app.ui.book.source

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.rssSourceManagementId
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise real Compose gestures, held Room publications, database order and restored lists. */
@RunWith(AndroidJUnit4::class)
class SourceDragOrderUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private enum class Kind {
        BOOK,
        RSS,
        REPLACE,
    }

    @Test fun filteredBookDragPreservesHiddenOrder() = verifyBookComposeDrag()

    @Test fun descendingBookDragPreservesHiddenOrder() = verifyBookComposeDrag(descending = true)

    @Test fun filteredRssDragPreservesHiddenOrder() = verifyRssComposeDrag()

    @Test fun filteredReplaceDragPreservesHiddenOrder() = verifyReplaceComposeDrag()

    @Test
    fun heldBookDragWithDeletedTargetDoesNotMoveAnotherSource() =
        verifyBookComposeDrag(removeTarget = true)

    private fun verifyBookComposeDrag(descending: Boolean = false, removeTarget: Boolean = false) {
        // Startup normalizes duplicate order values. Wait before inserting deliberate duplicate
        // fixtures so the test isolates held-drag persistence rather than racing housekeeping.
        waitUntil("application initialization completed") {
            (context.applicationContext as io.legado.app.App).initialization.isCompleted
        }
        val group = "Compose book drag ${UUID.randomUUID()}"
        val oldRows = appDb.bookSourceDao.allPart
        val help = LocalConfig.all["bookSourceHelpVersion"]
        val fixtures =
            listOf(100, 100, 400, 700, 900, 900).mapIndexed { index, order ->
                BookSource(
                    bookSourceUrl = "https://book-drag.invalid/$group/$index",
                    bookSourceName = "Book source $index $group",
                    bookSourceGroup = if (index % 2 == 0) group else "Hidden $group",
                    customOrder = order,
                    bookSourceComment = "Metadata $index",
                )
            }
        val visible =
            fixtures
                .filterIndexed { index, _ -> index % 2 == 0 }
                .let { if (descending) it.reversed() else it }
        val keys = visible.map { it.bookSourceUrl }
        val countBook =
            Book(
                bookUrl = "https://book-drag-count.invalid/$group",
                name = group,
                origin = keys.first(),
            )
        try {
            LocalConfig.edit().putInt("bookSourceHelpVersion", 1).commit()
            appDb.bookSourceDao.insert(*fixtures.toTypedArray())
            val before = appDb.bookSourceDao.allPart.map { it.bookSourceUrl }
            val rawOrders =
                appDb.bookSourceDao.allPart.associate { it.bookSourceUrl to it.customOrder }
            var metadata = databaseMetadata(Kind.BOOK)
            ActivityScenario.launch(BookSourceActivity::class.java).use { scenario ->
                waitUntil("book Compose manager loaded") {
                    var loaded = false
                    scenario.onActivity { loaded = !it.managerModel.state.value.loading }
                    loaded
                }
                val backBounds = compose.onNodeWithText(context.getString(R.string.back))
                    .fetchSemanticsNode().boundsInRoot
                scenario.onActivity { activity ->
                    val safeTop = checkNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
                        .getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
                    assertTrue("Book-source toolbar overlaps the status bar", backBounds.top >= safeTop)
                    assertTrue("Book-source toolbar adds excess top spacing", backBounds.top <= safeTop + activity.resources.displayMetrics.density * 8)
                }
                var needsDirectionChange = false
                scenario.onActivity { needsDirectionChange = it.managerModel.state.value.ascending == descending }
                if (needsDirectionChange) {
                    compose.onNode(hasText(context.getString(R.string.menu)) and isEnabled()).performClick()
                    compose.onNodeWithTag("source-manager-action:descending").performScrollTo().performClick()
                    waitUntil("book sort direction applied") {
                        var applied = false
                        scenario.onActivity { applied = it.managerModel.state.value.ascending == !descending }
                        applied
                    }
                }
                filter(Kind.BOOK, fixtures.first().bookSourceName)
                awaitItems(Kind.BOOK, scenario, listOf(fixtures.first().bookSourceUrl))
                filter(Kind.BOOK, "group:$group")
                fun waitRows(expected: List<String>) =
                    waitUntil("book Compose rows $expected") {
                        var matches = false
                        scenario.onActivity {
                            matches =
                                it.managerModel.state.value.rows.map { row -> row.url } == expected
                        }
                        matches && renderedOrderMatches(Kind.BOOK, expected)
                    }
                waitRows(keys)
                screenshot("book-$descending-$removeTarget-before")
                fun drag(returnToStart: Boolean, whileHeld: () -> Unit = {}) {
                    val list = compose.onNodeWithTag("source-manager-list")
                    val bounds = list.fetchSemanticsNode().boundsInRoot
                    val first =
                        compose
                            .onNodeWithTag("source-manager-drag:${keys.first()}")
                            .fetchSemanticsNode()
                            .boundsInRoot
                    val last =
                        compose
                            .onNodeWithTag("source-manager-drag:${keys.last()}")
                            .fetchSemanticsNode()
                            .boundsInRoot
                    val start = Offset(first.center.x - bounds.left, first.center.y - bounds.top)
                    val destination = Offset(start.x, last.center.y - bounds.top)
                    list.performTouchInput {
                        down(start)
                        advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 150)
                        moveTo(destination, 500)
                    }
                    try {
                        waitRows(keys.drop(1) + keys.first())
                        whileHeld()
                        if (returnToStart) {
                            list.performTouchInput {
                                moveTo(Offset(start.x, start.y - first.height / 4), 500)
                            }
                            waitRows(keys)
                        }
                    } finally {
                        list.performTouchInput { up() }
                    }
                }
                drag(true) {
                    appDb.bookDao.insert(countBook)
                    waitUntil("bookshelf update during held book drag") {
                        var updated = false
                        scenario.onActivity {
                            val state = it.managerModel.state.value
                            updated =
                                state.rows.getOrNull(2)?.url == keys.first() &&
                                    state.counts[keys.first()] == 1
                        }
                        updated
                    }
                    compose
                        .onNode(
                            hasText(context.getString(R.string.source_bookshelf_count, 1)) and
                                hasAnyAncestor(hasTestTag("source-manager-row:${keys.first()}"))
                        )
                        .assertExists()
                    assertEquals(before, appDb.bookSourceDao.allPart.map { it.bookSourceUrl })
                    appDb.bookDao.delete(countBook)
                }
                assertEquals(before, appDb.bookSourceDao.allPart.map { it.bookSourceUrl })
                assertEquals(
                    rawOrders,
                    appDb.bookSourceDao.allPart.associate { it.bookSourceUrl to it.customOrder },
                )
                drag(false) {
                    val refreshed = appDb.bookSourceDao.getBookSource(keys[1])!!
                    val updatedName = "Book publication ${UUID.randomUUID()}"
                    appDb.bookSourceDao.update(
                        refreshed.copy(
                            bookSourceName = updatedName,
                            bookSourceComment = "Edited during held book drag",
                        )
                    )
                    waitUntil("book name publication while pointer remains held") {
                        var published = false
                        scenario.onActivity { activity ->
                            published =
                                activity.managerModel.state.value.rows.any {
                                    it.url == keys[1] && it.name == updatedName
                                }
                        }
                        published
                    }
                    compose.onNodeWithText("$updatedName ($group)").assertExists()
                    if (removeTarget) appDb.bookSourceDao.delete(keys.last())
                    metadata = databaseMetadata(Kind.BOOK)
                    assertEquals(
                        if (removeTarget) before.filter { it != keys.last() } else before,
                        appDb.bookSourceDao.allPart.map { it.bookSourceUrl },
                    )
                }
                val expected =
                    if (removeTarget) before.filter { it != keys.last() }
                    else
                        before.toMutableList().apply {
                            remove(keys.first())
                            add(indexOf(keys.last()) + if (descending) 0 else 1, keys.first())
                        }
                waitUntil("book Compose drag committed") {
                    appDb.bookSourceDao.allPart.map { it.bookSourceUrl } == expected
                }
                assertEquals(
                    "Edited during held book drag",
                    appDb.bookSourceDao.getBookSource(keys[1])!!.bookSourceComment,
                )
                if (removeTarget)
                    assertEquals(
                        rawOrders.filterKeys { it != keys.last() },
                        appDb.bookSourceDao.allPart.associate {
                            it.bookSourceUrl to it.customOrder
                        },
                    )
                val expectedVisible =
                    expected.filter { it in keys }.let { if (descending) it.reversed() else it }
                assertEquals(metadata, databaseMetadata(Kind.BOOK))
                assertEquals(
                    before.filter { it != keys.first() && (!removeTarget || it != keys.last()) },
                    appDb.bookSourceDao.allPart
                        .map { it.bookSourceUrl }
                        .filter { it != keys.first() },
                )
                waitRows(expectedVisible)
                screenshot("book-$descending-$removeTarget-after")
                filter(Kind.BOOK, "")
                awaitItems(Kind.BOOK, scenario, if (descending) expected.reversed() else expected)
                filter(Kind.BOOK, "group:$group")
                waitRows(expectedVisible)
                scenario.recreate()
                waitRows(expectedVisible)
                screenshot("book-$descending-$removeTarget-recreated")
            }
        } finally {
            appDb.bookDao.delete(countBook)
            appDb.bookSourceDao.delete(*fixtures.toTypedArray())
            appDb.bookSourceDao.upOrder(oldRows)
            LocalConfig.edit()
                .apply {
                    if (help is Int) putInt("bookSourceHelpVersion", help)
                    else remove("bookSourceHelpVersion")
                }
                .commit()
        }
    }

    private fun verifyRssComposeDrag() {
        val group = "Compose drag ${UUID.randomUUID()}"
        val old = appDb.rssSourceDao.all
        val fixtures =
            listOf(100, 100, 400, 700, 900, 900).mapIndexed { index, order ->
                RssSource(
                    sourceUrl = "https://compose-drag.invalid/$group/$index",
                    sourceName = "Source $index $group",
                    sourceGroup = if (index % 2 == 0) group else "Hidden $group",
                    customOrder = order,
                    sourceComment = "Metadata $index",
                    ruleContent = "body@text",
                )
            }
        val visible = fixtures.filterIndexed { index, _ -> index % 2 == 0 }
        val ids = visible.map { rssSourceManagementId(it.sourceUrl) }
        try {
            appDb.rssSourceDao.insert(*fixtures.toTypedArray())
            val before = appDb.rssSourceDao.all.map { it.sourceUrl }
            val raw = appDb.rssSourceDao.all.associate { it.sourceUrl to it.customOrder }
            var metadata = databaseMetadata(Kind.RSS)
            ActivityScenario.launch(RssSourceActivity::class.java).use { scenario ->
                waitUntil("Compose RSS loaded") {
                    var ready = false
                    scenario.onActivity { ready = it.managementModel.state.value.loaded }
                    ready
                }
                filter(Kind.RSS, fixtures.first().sourceName)
                awaitItems(Kind.RSS, scenario, listOf(fixtures.first().sourceUrl))
                filter(Kind.RSS, "group:$group")
                fun waitRows(expected: List<String>) =
                    waitUntil("Compose RSS rows $expected") {
                        var ready = false
                        scenario.onActivity {
                            ready =
                                it.managementModel.state.value.rows.map { row -> row.id } ==
                                    expected
                        }
                        ready && renderedOrderMatches(Kind.RSS, expected, rssIds = true)
                    }
                waitRows(ids)
                screenshot("rss-before")
                fun drag(returnToStart: Boolean, whileHeld: () -> Unit = {}) {
                    val list = compose.onNodeWithTag("rss-source-list")
                    val bounds = list.fetchSemanticsNode().boundsInRoot
                    val first =
                        compose
                            .onNodeWithTag("rss-source-row-${ids[0]}")
                            .fetchSemanticsNode()
                            .boundsInRoot
                    val last =
                        compose
                            .onNodeWithTag("rss-source-row-${ids[2]}")
                            .fetchSemanticsNode()
                            .boundsInRoot
                    val start =
                        Offset(
                            8 * context.resources.displayMetrics.density,
                            first.center.y - bounds.top,
                        )
                    val end = Offset(start.x, last.center.y + last.height / 4 - bounds.top)
                    list.performTouchInput {
                        down(start)
                        advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 150)
                        moveTo(end, 500)
                    }
                    try {
                        waitRows(listOf(ids[1], ids[2], ids[0]))
                        whileHeld()
                        if (returnToStart) {
                            list.performTouchInput {
                                moveTo(Offset(start.x, start.y - first.height / 4), 500)
                            }
                            waitRows(ids)
                        }
                    } finally {
                        list.performTouchInput { up() }
                    }
                }
                drag(true)
                assertEquals(before, appDb.rssSourceDao.all.map { it.sourceUrl })
                assertEquals(
                    raw,
                    appDb.rssSourceDao.all.associate { it.sourceUrl to it.customOrder },
                )
                drag(false) {
                    val source = appDb.rssSourceDao.getByKey(visible[1].sourceUrl)!!
                    val updatedName = "RSS publication ${UUID.randomUUID()}"
                    appDb.rssSourceDao.update(
                        source.copy(sourceName = updatedName, sourceComment = "Edited during drag")
                    )
                    waitUntil("RSS publication buffered while pointer remains held") {
                        var published = false
                        scenario.onActivity { activity ->
                            published =
                                activity.managementModel.observedRows.any {
                                    it.id == ids[1] && it.name == updatedName
                                }
                        }
                        published
                    }
                    waitRows(listOf(ids[1], ids[2], ids[0]))
                    metadata = databaseMetadata(Kind.RSS)
                    assertEquals(before, appDb.rssSourceDao.all.map { it.sourceUrl })
                }
                val expected =
                    before.toMutableList().apply {
                        remove(visible[0].sourceUrl)
                        add(indexOf(visible[2].sourceUrl) + 1, visible[0].sourceUrl)
                    }
                waitUntil("Compose RSS committed") {
                    appDb.rssSourceDao.all.map { it.sourceUrl } == expected
                }
                assertEquals(
                    "Edited during drag",
                    appDb.rssSourceDao.getByKey(visible[1].sourceUrl)!!.sourceComment,
                )
                assertEquals(metadata, databaseMetadata(Kind.RSS))
                assertEquals(
                    before.filter { it != visible[0].sourceUrl },
                    appDb.rssSourceDao.all
                        .map { it.sourceUrl }
                        .filter { it != visible[0].sourceUrl },
                )
                waitRows(listOf(ids[1], ids[2], ids[0]))
                filter(Kind.RSS, "")
                awaitItems(Kind.RSS, scenario, expected)
                filter(Kind.RSS, "group:$group")
                waitRows(listOf(ids[1], ids[2], ids[0]))
                scenario.recreate()
                waitRows(listOf(ids[1], ids[2], ids[0]))
                screenshot("rss-recreated")
            }
        } finally {
            appDb.rssSourceDao.delete(*fixtures.toTypedArray())
            val orders = old.associate { it.sourceUrl to it.customOrder }
            appDb.rssSourceDao.update(
                *appDb.rssSourceDao.all
                    .map { it.copy(customOrder = orders[it.sourceUrl] ?: it.customOrder) }
                    .toTypedArray()
            )
        }
    }

    private fun verifyReplaceComposeDrag() {
        val group = "Drag ${UUID.randomUUID()}"
        val oldRules = appDb.replaceRuleDao.all
        val firstId = System.currentTimeMillis() * 1000
        val rules =
            listOf(100, 100, 400, 700, 900, 900).mapIndexed { index, order ->
                ReplaceRule(
                    id = firstId + index,
                    name = "Drag source $index $group",
                    group = if (index % 2 == 0) group else "Hidden $group",
                    order = order,
                    pattern = "original $index",
                    replacement = "replacement $index",
                    scope = "scope $index",
                )
            }
        val visible = rules.filterIndexed { index, _ -> index % 2 == 0 }.map { it.id.toString() }
        var scenario: ActivityScenario<out Activity>? = null
        try {
            appDb.replaceRuleDao.insert(*rules.toTypedArray())
            val before = databaseKeys(Kind.REPLACE)
            val rawOrders = databaseOrders(Kind.REPLACE)
            scenario = launch(Kind.REPLACE)
            val activeScenario = checkNotNull(scenario)
            awaitLoaded(Kind.REPLACE, activeScenario)
            filter(Kind.REPLACE, rules.first().name)
            awaitItems(Kind.REPLACE, activeScenario, listOf(rules.first().id.toString()))
            filter(Kind.REPLACE, "group:$group")
            awaitItems(Kind.REPLACE, activeScenario, visible)
            screenshot("drag-REPLACE-before")

            // A complete round trip with the same held pointer must not renumber duplicate orders.
            drag(Kind.REPLACE, activeScenario, visible, returnToStart = true)
            awaitItems(Kind.REPLACE, activeScenario, visible)
            assertEquals(before, databaseKeys(Kind.REPLACE))
            assertEquals(rawOrders, databaseOrders(Kind.REPLACE))

            var metadata = databaseMetadata(Kind.REPLACE)
            drag(Kind.REPLACE, activeScenario, visible) {
                val updatedName = "Replacement publication ${UUID.randomUUID()}"
                val refreshed = checkNotNull(appDb.replaceRuleDao.findById(visible[1].toLong()))
                appDb.replaceRuleDao.update(
                    refreshed.copy(name = updatedName, replacement = "Refreshed while dragging")
                )
                metadata = databaseMetadata(Kind.REPLACE)
                waitUntil("Room publication during held replacement drag") {
                    var published = false
                    activeScenario.onActivity { activity ->
                        published =
                            (activity as ReplaceRuleActivity).managementModel.observedRows.any {
                                it.id.toString() == visible[1] && it.name == updatedName
                            }
                    }
                    published
                }
                awaitItems(Kind.REPLACE, activeScenario, visible.drop(1) + visible.first())
                assertEquals(before, databaseKeys(Kind.REPLACE))
                screenshot("drag-REPLACE-held-refresh")
            }
            val expected =
                before.toMutableList().apply {
                    remove(visible.first())
                    add(indexOf(visible.last()) + 1, visible.first())
                }
            waitUntil("persisted replacement drag order") { databaseKeys(Kind.REPLACE) == expected }
            assertEquals(
                before.filter { it != visible.first() },
                databaseKeys(Kind.REPLACE).filter { it != visible.first() },
            )
            assertEquals(metadata, databaseMetadata(Kind.REPLACE))
            awaitItems(Kind.REPLACE, activeScenario, visible.drop(1) + visible.first())
            screenshot("drag-REPLACE-after")
            filter(Kind.REPLACE, "")
            awaitItems(Kind.REPLACE, activeScenario, expected)
            filter(Kind.REPLACE, "group:$group")
            activeScenario.recreate()
            awaitItems(Kind.REPLACE, activeScenario, expected.filter { it in visible })
            activeScenario.close()
            scenario = launch(Kind.REPLACE)
            val reopened = checkNotNull(scenario)
            awaitLoaded(Kind.REPLACE, reopened)
            filter(Kind.REPLACE, "")
            awaitItems(Kind.REPLACE, reopened, expected)
            filter(Kind.REPLACE, "group:$group")
            awaitItems(Kind.REPLACE, reopened, expected.filter { it in visible })
            screenshot("drag-REPLACE-reopened")
        } finally {
            scenario?.close()
            appDb.replaceRuleDao.delete(*rules.toTypedArray())
            val savedOrders = oldRules.associate { it.id to it.order }
            appDb.replaceRuleDao.update(
                *appDb.replaceRuleDao.all
                    .map {
                        it.copy(order = savedOrders[it.id] ?: it.order)
                    }
                    .toTypedArray()
            )
        }
    }

    private fun launch(kind: Kind): ActivityScenario<out Activity> =
        when (kind) {
            Kind.BOOK -> ActivityScenario.launch(BookSourceActivity::class.java)
            Kind.RSS -> ActivityScenario.launch(RssSourceActivity::class.java)
            Kind.REPLACE -> ActivityScenario.launch(ReplaceRuleActivity::class.java)
        }

    private fun databaseKeys(kind: Kind): List<String> =
        when (kind) {
            Kind.BOOK -> appDb.bookSourceDao.allPart.map { it.bookSourceUrl }
            Kind.RSS -> appDb.rssSourceDao.all.map { it.sourceUrl }
            Kind.REPLACE -> appDb.replaceRuleDao.all.map { it.id.toString() }
        }

    private fun databaseOrders(kind: Kind): Map<String, Int> =
        when (kind) {
            Kind.BOOK ->
                appDb.bookSourceDao.allPart.associate { it.bookSourceUrl to it.customOrder }
            Kind.RSS -> appDb.rssSourceDao.all.associate { it.sourceUrl to it.customOrder }
            Kind.REPLACE -> appDb.replaceRuleDao.all.associate { it.id.toString() to it.order }
        }

    private fun databaseMetadata(kind: Kind): Map<String, String> =
        when (kind) {
            Kind.BOOK ->
                appDb.bookSourceDao.all.associate {
                    it.bookSourceUrl to GSON.toJson(it.copy(customOrder = 0))
                }
            Kind.RSS ->
                appDb.rssSourceDao.all.associate {
                    it.sourceUrl to GSON.toJson(it.copy(customOrder = 0))
                }
            Kind.REPLACE ->
                appDb.replaceRuleDao.all.associate {
                    it.id.toString() to GSON.toJson(it.copy(order = 0))
                }
        }

    private fun searchTag(kind: Kind) =
        when (kind) {
            Kind.BOOK -> "source-manager-search"
            Kind.RSS -> "rss-source-search"
            Kind.REPLACE -> "replace-rule-search"
        }

    private fun listTag(kind: Kind) =
        when (kind) {
            Kind.BOOK -> "source-manager-list"
            Kind.RSS -> "rss-source-list"
            Kind.REPLACE -> "replace-rule-list"
        }

    private fun rowTag(kind: Kind, key: String, rssIds: Boolean = false) =
        when (kind) {
            Kind.BOOK -> "source-manager-row:$key"
            Kind.RSS -> "rss-source-row-${if (rssIds) key else rssSourceManagementId(key)}"
            Kind.REPLACE -> "replace-rule-row-$key"
        }

    private fun filter(kind: Kind, query: String) {
        compose.onNodeWithTag(searchTag(kind)).performTextReplacement(query)
        if (kind != Kind.BOOK) compose.onNodeWithTag(searchTag(kind)).performImeAction()
        closeSoftKeyboard()
        compose.onNodeWithTag(searchTag(kind)).assertTextContains(query)
    }

    private fun awaitLoaded(kind: Kind, scenario: ActivityScenario<out Activity>) {
        waitUntil("$kind manager ready") {
            var loaded = false
            scenario.onActivity { activity ->
                loaded =
                    when (kind) {
                        Kind.BOOK ->
                            !(activity as BookSourceActivity).managerModel.state.value.loading
                        Kind.RSS ->
                            (activity as RssSourceActivity).managementModel.state.value.loaded
                        Kind.REPLACE ->
                            (activity as ReplaceRuleActivity).managementModel.state.value.loaded
                    }
            }
            loaded
        }
        compose.onNodeWithTag(listTag(kind)).assertExists()
    }

    private fun awaitItems(
        kind: Kind,
        scenario: ActivityScenario<out Activity>,
        expected: List<String>,
    ) {
        waitUntil("visible $kind order $expected") {
            var matches = false
            scenario.onActivity { activity ->
                val items =
                    when (kind) {
                        Kind.BOOK ->
                            (activity as BookSourceActivity).managerModel.state.value.rows.map {
                                it.url
                            }
                        Kind.RSS ->
                            (activity as RssSourceActivity).managementModel.state.value.rows.map {
                                it.id
                            }
                        Kind.REPLACE ->
                            (activity as ReplaceRuleActivity).managementModel.state.value.rows.map {
                                it.id.toString()
                            }
                    }
                val expectedIds =
                    if (kind == Kind.RSS) expected.map(::rssSourceManagementId) else expected
                matches = items == expectedIds
            }
            matches && (expected.size > 3 || renderedOrderMatches(kind, expected))
        }
        compose.onNodeWithTag(listTag(kind)).assertExists()
    }

    private fun renderedOrderMatches(
        kind: Kind,
        expected: List<String>,
        rssIds: Boolean = false,
    ): Boolean {
        return try {
            val bounds = expected.map {
                compose.onNodeWithTag(rowTag(kind, it, rssIds)).fetchSemanticsNode().boundsInRoot
            }
            bounds.all { it.height > 0 } &&
                bounds.zipWithNext().all { (first, next) -> first.center.y < next.center.y }
        } catch (_: AssertionError) {
            false
        }
    }

    private fun drag(
        kind: Kind,
        scenario: ActivityScenario<out Activity>,
        original: List<String>,
        returnToStart: Boolean = false,
        whileHeld: () -> Unit = {},
    ) {
        val list = compose.onNodeWithTag(listTag(kind))
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val first =
            compose.onNodeWithTag(rowTag(kind, original.first())).fetchSemanticsNode().boundsInRoot
        val last =
            compose.onNodeWithTag(rowTag(kind, original.last())).fetchSemanticsNode().boundsInRoot
        // Stay outside the checkbox slide-selection band, matching the original drag baseline.
        val start =
            Offset(8 * context.resources.displayMetrics.density, first.center.y - bounds.top)
        val end = Offset(start.x, last.center.y + last.height / 4 - bounds.top)
        var returned = false
        list.performTouchInput {
            down(start)
            advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            moveTo(end, 500)
        }
        try {
            awaitItems(kind, scenario, original.drop(1) + original.first())
            whileHeld()
            if (returnToStart) {
                list.performTouchInput { moveTo(Offset(start.x, start.y - first.height / 4), 500) }
                awaitItems(kind, scenario, original)
                returned = true
            }
        } catch (failure: Throwable) {
            screenshot("drag-held-failure-${SystemClock.uptimeMillis()}")
            throw failure
        } finally {
            list.performTouchInput { up() }
        }
        if (returned) awaitItems(kind, scenario, original)
    }

    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(context.getExternalFilesDir(null), "ui-regression/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.mainClock.advanceTimeByFrame()
                condition()
            }
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            error("Timed out waiting for $description")
        }
    }
}
