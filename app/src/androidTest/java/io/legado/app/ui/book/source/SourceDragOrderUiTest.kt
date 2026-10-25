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
import io.legado.app.ui.flutter.FlutterUiTestDriver
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise real rendered gestures, held Room publications, database order and restored lists. */
@RunWith(AndroidJUnit4::class)
class SourceDragOrderUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val flutter = FlutterUiTestDriver(instrumentation)

    private enum class Kind {
        BOOK,
        RSS,
        REPLACE,
    }

    @Test fun filteredBookDragPreservesHiddenOrder() = verifyBookComposeDrag()

    @Test fun descendingBookDragPreservesHiddenOrder() = verifyBookComposeDrag(descending = true)

    @Test fun filteredRssDragPreservesHiddenOrder() = verifyRssComposeDrag()

    @Test fun filteredReplaceDragPreservesHiddenOrder() = verifyReplaceFlutterDrag()

    @Test
    fun heldBookDragWithDeletedTargetDoesNotMoveAnotherSource() =
        verifyBookComposeDrag(removeTarget = true)

    private fun verifyBookComposeDrag(descending: Boolean = false, removeTarget: Boolean = false) {
        flutter.enableAccessibility()
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
                flutter.awaitIdentifier("source-manager-query")
                val queryBounds = flutter.bounds("source-manager-query")
                scenario.onActivity { activity ->
                    val safeTop = checkNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
                        .getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
                    assertTrue("Book-source search overlaps the status bar", queryBounds.top >= safeTop)
                }
                var needsDirectionChange = false
                scenario.onActivity { needsDirectionChange = it.managerModel.state.value.ascending == descending }
                if (needsDirectionChange) {
                    flutter.click("source-manager-menu")
                    flutter.clickScrollable("source-manager-action:descending")
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
                    val first = flutter.bounds("source-manager-row-menu:${keys.first()}")
                    val last = flutter.bounds("source-manager-row-menu:${keys.last()}")
                    val startX = first.exactCenterX()
                    val startY = first.exactCenterY()
                    val destinationY = last.exactCenterY() + last.height() / 4f
                    flutter.touchDown(startX, startY).use { touch ->
                        touch.hold(ViewConfiguration.getLongPressTimeout().toLong() + 200)
                        // The Android hold does not acknowledge Flutter's delayed recognizer.
                        // Keep the real pointer still until beginDrag arrives before moving it;
                        // early movement can reject that recognizer while its Dart timer waits.
                        // Recognition and the original target assertion share one 30s budget.
                        val deadline = SystemClock.uptimeMillis() + 30_000
                        var receipt = "no native drag receipt"
                        fun dragging(): Boolean {
                            var matched = false
                            scenario.onActivity { activity ->
                                val state = activity.managerModel.state.value
                                matched = state.draggingKey == keys.first()
                                receipt = "nativeDraggingMatches=$matched,canMove=${state.canMove}," +
                                    "loading=${state.loading},busy=${state.busy},ascending=${state.ascending}," +
                                    "rowCount=${state.rows.size}," +
                                    "visibleFixtureOrder=${state.rows.take(4).map { keys.indexOf(it.url) }}," +
                                    "viewport=${activity.window.decorView.width}x${activity.window.decorView.height}"
                            }
                            return matched
                        }
                        fun awaitHeld(description: String, condition: () -> Boolean) {
                            while (SystemClock.uptimeMillis() < deadline) {
                                if (condition()) return
                                SystemClock.sleep(minOf(50L,
                                    (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
                            }
                            val matched = condition()
                            assertTrue("$description; $receipt; start=$first,target=$last", matched)
                        }
                        try {
                            awaitHeld("Flutter acknowledges the held drag before movement", ::dragging)
                            touch.moveTo(startX, destinationY)
                            awaitHeld("Flutter held drag reaches the target row") {
                                val started = dragging()
                                val bounds = flutter.node("source-manager-row-menu:${keys.first()}")
                                val moved = bounds?.let { node ->
                                    val rect = android.graphics.Rect()
                                    node.getBoundsInScreen(rect)
                                    node.recycle()
                                    receipt += ",actualHeldBounds=$rect"
                                    rect.centerY() > startY + first.height()
                                } ?: false
                                started && moved
                            }
                        } catch (failure: AssertionError) {
                            runCatching { screenshot("book-held-failure-${SystemClock.uptimeMillis()}") }
                                .exceptionOrNull()?.let(failure::addSuppressed)
                            throw failure
                        }
                        // Flutter owns the held visual preview; native order commits only after drop.
                        whileHeld()
                        if (returnToStart) {
                            touch.moveTo(startX, startY - first.height() / 4f)
                        }
                    }
                    flutter.await("Native drag session finishes") {
                        var finished = false
                        scenario.onActivity { finished = it.managerModel.state.value.draggingKey == null }
                        finished
                    }
                    if (returnToStart) {
                        waitRows(keys)
                        // onReorderEnd publishes native cancellation before Flutter's
                        // return animation removes its proxy. Starting another pointer
                        // on that proxy cannot start a new reorder gesture.
                        flutter.await("Flutter return animation restores the original row bounds") {
                            fun matches(key: String, expected: android.graphics.Rect): Boolean =
                                flutter.node("source-manager-row-menu:$key")?.let { node ->
                                    val actual = android.graphics.Rect()
                                    node.getBoundsInScreen(actual)
                                    node.recycle()
                                    actual == expected
                                } ?: false
                            matches(keys.first(), first) && matches(keys.last(), last)
                        }
                    }
                }
                drag(true) {
                    appDb.bookDao.insert(countBook)
                    waitUntil("bookshelf update during held book drag") {
                        var updated = false
                        scenario.onActivity {
                            val state = it.managerModel.state.value
                            updated =
                                state.draggingKey == keys.first() &&
                                    state.counts[keys.first()] == 1
                        }
                        updated
                    }
                    flutter.await("Flutter renders live bookshelf count during held drag") {
                        flutter.containsText("source-manager-row:${keys.first()}", "书架 1 本") ||
                            flutter.containsText("source-manager-row:${keys.first()}", "1 books in library")
                    }
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
                    flutter.await("Flutter renders publication during held drag") {
                        flutter.containsText("source-manager-row:${keys[1]}", updatedName)
                    }
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
                if (!descending && !removeTarget) {
                    // Distinguish a rejected/stale query from native order and viewport failures.
                    // This is the original predicate and 30s wait: no input or draw is repeated.
                    val receipts = arrayListOf<String>()
                    try {
                        waitUntil("book filtered rows after restoring group") {
                            var nativeMatches = false
                            var receipt = "native not sampled"
                            scenario.onActivity { activity ->
                                val state = activity.managerModel.state.value
                                nativeMatches = state.rows.map { it.url } == expectedVisible
                                val decor = activity.window.decorView
                                val insets = ViewCompat.getRootWindowInsets(decor)
                                receipt = "queryMatches=${state.query == "group:$group"},loading=${state.loading}," +
                                    "busy=${state.busy},nativeCount=${state.rows.size},nativeOrder=${state.rows.take(24).map { keys.indexOf(it.url) }}," +
                                    "nativeMatches=$nativeMatches,focused=${decor.hasWindowFocus()}," +
                                    "viewport=${decor.width}x${decor.height}," +
                                    "imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())}," +
                                    "imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}"
                            }
                            val actualQuery = flutter.node("source-manager-query")
                            receipt += actualQuery?.let { node ->
                                try { ",actualQueryMatches=${node.text?.toString() == "group:$group"}" }
                                finally { node.recycle() }
                            } ?: ",actualQueryMissing=true"
                            val bounds = expectedVisible.mapIndexed { index, key ->
                                flutter.node("source-manager-row:$key")?.let { node ->
                                    try {
                                        val rect = android.graphics.Rect().also(node::getBoundsInScreen)
                                        receipt += ";row$index=$rect,visible=${node.isVisibleToUser},window=${node.windowId}"
                                        rect
                                    } finally { node.recycle() }
                                }.also { if (it == null) receipt += ";row$index=missing" }
                            }
                            val renderedMatches = bounds.all { it != null && it.height() > 0 } &&
                                bounds.zipWithNext().all { (first, next) -> first!!.centerY() < next!!.centerY() }
                            receipt += ",renderedMatches=$renderedMatches"
                            if (receipt != receipts.lastOrNull()) {
                                if (receipts.size == 24) receipts.removeAt(0)
                                receipts += receipt
                            }
                            nativeMatches && renderedMatches
                        }
                    } catch (failure: AssertionError) {
                        val output = File(context.getExternalFilesDir(null), "ui-regression/book-filter-restore-receipt.txt")
                        runCatching {
                            output.parentFile!!.mkdirs()
                            output.writeText(receipts.joinToString("\n"))
                            screenshot("book-filter-restore-failure")
                        }.onFailure(failure::addSuppressed)
                        failure.addSuppressed(AssertionError(receipts.lastOrNull() ?: "no filter receipt"))
                        throw failure
                    }
                } else {
                    waitRows(expectedVisible)
                }
                scenario.recreate()
                waitRows(expectedVisible)
                screenshot("book-$descending-$removeTarget-recreated")
            }
        } finally {
            appDb.bookDao.delete(countBook)
            appDb.bookSourceDao.delete(*fixtures.toTypedArray())
            appDb.bookSourceDao.upOrder(oldRows)
            flutter.close()
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
            flutter.enableAccessibility()
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
                    val first = flutter.bounds("rss-manager-reorder-${ids[0]}")
                    val last = flutter.bounds("rss-manager-reorder-${ids[2]}")
                    val startX = first.exactCenterX()
                    val startY = first.exactCenterY()
                    flutter.touchDown(startX, startY).use { touch ->
                        touch.hold(ViewConfiguration.getLongPressTimeout().toLong() + 150)
                        touch.moveTo(startX, last.exactCenterY() + last.height() / 4f)
                        waitUntil("Flutter RSS held proxy reaches the target row") {
                            var dragging = false
                            scenario.onActivity {
                                dragging = it.managementModel.state.value.dragging == ids[0]
                            }
                            val moved = flutter.node("rss-manager-reorder-${ids[0]}")?.let { node ->
                                val rect = android.graphics.Rect()
                                node.getBoundsInScreen(rect)
                                node.recycle()
                                rect.centerY() > startY + first.height()
                            } ?: false
                            dragging && moved
                        }
                        // Flutter holds the reorder proxy; native order commits only on drop.
                        whileHeld()
                        if (returnToStart) touch.moveTo(startX, startY - first.height() / 4f)
                    }
                    waitUntil("RSS native drag session finishes") {
                        var finished = false
                        scenario.onActivity { finished = it.managementModel.state.value.dragging == null }
                        finished
                    }
                    if (returnToStart) {
                        waitRows(ids)
                        waitUntil("Flutter RSS return animation restores both row bounds") {
                            fun matches(id: String, expected: android.graphics.Rect): Boolean =
                                flutter.node("rss-manager-reorder-$id")?.let { node ->
                                    val actual = android.graphics.Rect()
                                    node.getBoundsInScreen(actual)
                                    node.recycle()
                                    actual == expected
                                } ?: false
                            matches(ids[0], first) && matches(ids[2], last)
                        }
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
                    // Room publication is buffered while the real Flutter pointer is held.
                    scenario.onActivity {
                        assertEquals(ids[0], it.managementModel.state.value.dragging)
                        assertEquals(ids, it.managementModel.state.value.rows.map { row -> row.id })
                    }
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
            flutter.close()
            appDb.rssSourceDao.delete(*fixtures.toTypedArray())
            val orders = old.associate { it.sourceUrl to it.customOrder }
            appDb.rssSourceDao.update(
                *appDb.rssSourceDao.all
                    .map { it.copy(customOrder = orders[it.sourceUrl] ?: it.customOrder) }
                    .toTypedArray()
            )
        }
    }

    private fun verifyReplaceFlutterDrag() {
        flutter.enableAccessibility()
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
            flutter.close()
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
        if (kind == Kind.REPLACE) {
            flutter.setText("replace-rule-search", query)
            closeSoftKeyboard()
            waitUntil("actual replacement query reflects input") { flutter.text("replace-rule-search") == query }
            return
        }
        if (kind == Kind.BOOK || kind == Kind.RSS) {
            val identifier = if (kind == Kind.BOOK) "source-manager-query" else "rss-manager-query"
            flutter.setText(identifier, query)
            closeSoftKeyboard()
            flutter.await("Flutter query reflects input") { flutter.text(identifier) == query }
            return
        }
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
        when (kind) {
            Kind.BOOK -> flutter.awaitIdentifier("source-manager-query")
            Kind.RSS -> flutter.awaitIdentifier("rss-manager-query")
            Kind.REPLACE -> flutter.awaitIdentifier("replace-rule-list")
        }
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
        when (kind) {
            Kind.BOOK -> flutter.awaitIdentifier("source-manager-query")
            Kind.RSS -> flutter.awaitIdentifier("rss-manager-query")
            Kind.REPLACE -> flutter.awaitIdentifier("replace-rule-list")
        }
    }

    private fun renderedOrderMatches(
        kind: Kind,
        expected: List<String>,
        rssIds: Boolean = false,
    ): Boolean {
        if (kind == Kind.REPLACE) {
            val bounds = expected.map { key ->
                val node = flutter.node("replace-rule-row-$key") ?: return false
                android.graphics.Rect().also { node.getBoundsInScreen(it); node.recycle() }
            }
            return bounds.all { it.height() > 0 } &&
                bounds.zipWithNext().all { (first, next) -> first.centerY() < next.centerY() }
        }
        if (kind == Kind.BOOK || kind == Kind.RSS) {
            val bounds = expected.map { key ->
                val identifier = if (kind == Kind.BOOK) "source-manager-row:$key"
                    else "rss-manager-row-${if (rssIds) key else rssSourceManagementId(key)}"
                val node = flutter.node(identifier) ?: return false
                android.graphics.Rect().also { node.getBoundsInScreen(it); node.recycle() }
            }
            return bounds.all { it.height() > 0 } &&
                bounds.zipWithNext().all { (first, next) -> first.centerY() < next.centerY() }
        }
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
        if (kind == Kind.REPLACE) {
            var previousGeometry: Pair<android.graphics.Rect, android.graphics.Rect>? = null
            waitUntil("replacement IME hidden and actual drag geometry settled") {
                var imeHidden = false
                scenario.onActivity { activity ->
                    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    imeHidden = insets != null && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0
                }
                val firstNode = flutter.node("replace-rule-row-${original.first()}")
                val lastNode = flutter.node("replace-rule-row-${original.last()}")
                if (firstNode == null || lastNode == null) {
                    firstNode?.recycle(); lastNode?.recycle()
                    return@waitUntil false
                }
                val next = android.graphics.Rect().also { firstNode.getBoundsInScreen(it); firstNode.recycle() } to
                    android.graphics.Rect().also { lastNode.getBoundsInScreen(it); lastNode.recycle() }
                val stable = next == previousGeometry
                previousGeometry = next
                val controls = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                flutter.node("replace-rule-enabled-${original.first()}")?.let(controls::add)
                var acknowledged = false
                while (controls.isNotEmpty()) {
                    val node = controls.removeFirst()
                    if (node.isVisibleToUser && node.isCheckable && node.isEnabled && node.isClickable &&
                        node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK }) acknowledged = true
                    for (index in 0 until node.childCount) node.getChild(index)?.let(controls::add)
                    node.recycle()
                }
                imeHidden && stable && acknowledged
            }
            val first = flutter.bounds("replace-rule-row-${original.first()}")
            val last = flutter.bounds("replace-rule-row-${original.last()}")
            // Long-press the name band, outside checkbox slide-selection and row actions.
            val x = first.left + minOf(first.width() / 2f, 80 * context.resources.displayMetrics.density)
            var returned = false
            flutter.touchDown(x, first.exactCenterY()).use { pointer ->
                pointer.hold(ViewConfiguration.getLongPressTimeout().toLong() + 150)
                // A wall-clock hold is not a Flutter/native beginDrag acknowledgement.
                // Keep this real pointer still until the native drag starts. Recognition,
                // movement and the original held-preview assertion share one 30s budget.
                val deadline = SystemClock.uptimeMillis() + 30_000
                var receipt = "no native replacement drag receipt"
                fun awaitHeld(description: String, condition: () -> Boolean) {
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (condition()) return
                        SystemClock.sleep(minOf(50L,
                            (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
                    }
                    val matched = condition()
                    assertTrue("$description; $receipt", matched)
                }
                fun nativeMatches(expectedOrder: List<String>? = null): Boolean {
                    var matched = false
                    scenario.onActivity { activity ->
                        val state = (activity as ReplaceRuleActivity).managementModel.state.value
                        val dragging = state.dragging?.toString() == original.first()
                        val rows = state.rows.map { it.id.toString() }
                        receipt = "nativeDraggingMatches=$dragging,loaded=${state.loaded},busy=${state.busy}," +
                            "rowCount=${rows.size},fixtureOrder=${rows.take(4).map(original::indexOf)}," +
                            "viewport=${activity.window.decorView.width}x${activity.window.decorView.height}"
                        matched = dragging && (expectedOrder == null || rows == expectedOrder)
                    }
                    return matched
                }
                try {
                    awaitHeld("Native replacement drag begins before movement") { nativeMatches() }
                    pointer.moveTo(x, last.exactCenterY() + last.height() / 4f, 500)
                    val expected = original.drop(1) + original.first()
                    awaitHeld("visible $kind order $expected") {
                        nativeMatches(expected) && renderedOrderMatches(kind, expected)
                    }
                    whileHeld()
                    if (returnToStart) {
                        pointer.moveTo(x, first.exactCenterY() - first.height() / 4f, 500)
                        awaitItems(kind, scenario, original)
                        returned = true
                    }
                } catch (failure: Throwable) {
                    runCatching {
                        val heldBounds = original.map { id ->
                            flutter.node("replace-rule-row-$id")?.let { node ->
                                android.graphics.Rect().also { node.getBoundsInScreen(it); node.recycle() }
                            }
                        }
                        failure.addSuppressed(AssertionError("$receipt; expectedStart=$first,target=$last,heldBounds=$heldBounds"))
                    }.exceptionOrNull()?.let(failure::addSuppressed)
                    runCatching { screenshot("drag-held-failure-${SystemClock.uptimeMillis()}") }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            }
            if (returned) awaitItems(kind, scenario, original)
            return
        }
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
        flutter.await(description, condition)
    }
}
