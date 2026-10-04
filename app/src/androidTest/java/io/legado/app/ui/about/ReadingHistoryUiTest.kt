package io.legado.app.ui.about

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.testutil.saveSemantics
import java.io.File
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.legado.app.data.image.CoverImage
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*

class ReadingHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val white =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    private val covers =
        object : ReadingHistoryCoverRepository {
            override suspend fun load(
                cover: ReadingHistoryCover,
                fallback: String?,
                width: Int,
                height: Int,
            ) = ReadingHistoryCoverResult(CoverImage.Static(white), true)
        }

    private fun row(index: Int = 0) =
        ReadingHistoryRow(
            ReadingHistoryIdentity("Title $index", "Author $index"),
            "Author $index",
            listOf("Author $index"),
            false,
            3000,
            0,
            "Saved chapter",
            ReadingHistoryCover(null, null, null, false),
        )

    private fun initial(simple: Boolean = true) =
        ReadingHistoryState(
            snapshot = ReadingHistorySnapshot(listOf(row()), 1, 3000, listOf(row())),
            preferences = ReadingHistoryPreferences(simple = simple),
            ready = true,
            loading = false,
        )

    private fun actions(
        open: (ReadingHistoryIdentity) -> Unit = {},
        delete: (ReadingHistoryIdentity) -> Unit = {},
        query: (String) -> Unit = {},
        pref: (ReadingHistoryPreference, ReadingHistoryPreferences) -> Unit = { _, _ -> },
        confirm: () -> Unit = {},
    ) = ReadingHistoryActions(query, pref, open, delete, {}, { confirm() }, {}, {}, {}, {}, {}, {})

    @Test
    fun bothLayoutsPutAuthorBeforeDurationAndDeleteUsesExactIdentityWithoutOpeningReader() {
        var state by mutableStateOf(initial())
        val opens = mutableListOf<ReadingHistoryIdentity>()
        val deletes = mutableListOf<ReadingHistoryIdentity>()
        val row = row()
        compose.setContent {
            LegadoComposeTheme {
                ReadingHistoryScreen(
                    state,
                    actions(open = { opens += it }, delete = { deletes += it }),
                    covers,
                )
            }
        }
        for (simple in listOf(true, false)) {
            compose.runOnIdle {
                state = state.copy(preferences = state.preferences.copy(simple = simple))
            }
            val author =
                compose
                    .onNodeWithTag("history-author-${row.key}", true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val time =
                compose
                    .onNodeWithTag("history-time-${row.key}", true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(author.bottom <= time.top)
            compose.onNodeWithTag("history-date-${row.key}", true).assertTextEquals("")
            compose.onNodeWithTag("history-delete-${row.key}", true).performClick()
            assertTrue(opens.isEmpty())
            assertEquals(row.identity, deletes.last())
        }
        compose.onNodeWithTag("history-row-${row.key}").performClick()
        assertEquals(listOf(row.identity), opens)
    }

    @Test
    fun narrowLargeFontKeepsSummaryAndAllEnhancedFieldsSeparatedAndAccessible() {
        val row = row().copy(lastRead = 1_700_000_000_000L)
        val state = initial(false).copy(
            snapshot = ReadingHistorySnapshot(listOf(row), 1, row.readTime, listOf(row)),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.4f)) {
                LegadoComposeTheme {
                    Box(Modifier.width(280.dp)) {
                        ReadingHistoryScreen(state, actions(), covers)
                    }
                }
            }
        }
        compose.onNodeWithTag("history-summary").assertIsDisplayed()
        // Sample the actual fields after the user can scroll the last field into the viewport.
        compose.onNodeWithTag("history-date-${row.key}", true).performScrollTo().assertIsDisplayed()
        val names = listOf("title", "author", "chapter", "time", "date")
        val fields = names.map {
            compose.onNodeWithTag("history-$it-${row.key}", true).fetchSemanticsNode().boundsInRoot
        }
        try {
            fields.zipWithNext().forEachIndexed { index, (a, b) ->
                assertTrue("${names[index]}=$a must precede ${names[index + 1]}=$b; all=${names.zip(fields)}", a.bottom <= b.top)
            }
            assertTrue("All enhanced fields have nonempty bounds: ${names.zip(fields)}", fields.all { it.width > 0 && it.height > 0 })
            compose.onNodeWithTag("history-delete-${row.key}", true).assertHasClickAction()
        } catch (failure: AssertionError) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            runCatching { compose.saveSemantics(context, "history-narrow-large-font-failure") }
            runCatching {
                val directory = File(context.getExternalFilesDir(null), "ui-regression").apply { mkdirs() }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    try { File(directory, "history-narrow-large-font-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
            }
            throw failure
        }
    }

    @Test
    fun listScrollAndOpenMenuRestoreWithStableRowKeysAfterRecreation() {
        val rows = (0..80).map(::row)
        val state =
            initial(false)
                .copy(
                    snapshot =
                        ReadingHistorySnapshot(
                            rows,
                            rows.size,
                            rows.sumOf { it.readTime },
                            rows.take(3),
                        ),
                    preferences = ReadingHistoryPreferences(simple = false, fixed = false),
                )
        val tester = StateRestorationTester(compose)
        tester.setContent { LegadoComposeTheme { ReadingHistoryScreen(state, actions(), covers) } }
        compose.onNodeWithTag("history-list").performScrollToIndex(40)
        compose.onNodeWithTag("history-row-${rows[39].key}").assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history-row-${rows[39].key}").assertIsDisplayed()
        compose.onNodeWithTag("history-summary").assertDoesNotExist()
        compose.onNodeWithTag("history-menu").performClick()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history-pref-Days").assertIsDisplayed()
    }

    @Test
    fun searchAndSortAreRealCallbacksAndRestoredConfirmationNeverAutoDeletes() {
        var query = ""
        val sorts = mutableListOf<Int>()
        var deletes = 0
        var state by
            mutableStateOf(
                initial().copy(confirmation = ReadingHistoryConfirmation(row().identity))
            )
        val tester = StateRestorationTester(compose)
        tester.setContent {
            LegadoComposeTheme {
                ReadingHistoryScreen(
                    state,
                    actions(
                        query = { query = it },
                        pref = { _, p -> sorts += p.sort },
                        confirm = {
                            deletes++
                            state = state.copy(confirmation = null)
                        },
                    ),
                    covers,
                )
            }
        }
        tester.emulateSavedInstanceStateRestore()
        assertEquals(0, deletes)
        compose.onNodeWithTag("history-confirm").performClick()
        assertEquals(1, deletes)
        compose.onNodeWithTag("history-search").performTextReplacement("Author")
        assertEquals("Author", query)
        compose.onNodeWithTag("history-sort").performClick()
        compose.onNodeWithTag("history-sort-2").performClick()
        assertEquals(listOf(2), sorts)
    }
    @Test
    fun cancelledCoverCleanupCannotClearTheNewSizeResult() {
        val firstStarted = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val replacementLoaded = CompletableDeferred<Unit>()
        val color = Color.rgb(35, 148, 115)
        val green = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        var density by mutableStateOf(1f)
        val screenState = initial(false).let { it.copy(snapshot = it.snapshot.copy(top = emptyList())) }
        val controlled = object : ReadingHistoryCoverRepository {
            override suspend fun load(
                cover: ReadingHistoryCover,
                fallback: String?,
                width: Int,
                height: Int,
            ): ReadingHistoryCoverResult {
                // No summary covers are present; only this row restarts at the new physical size.
                if (height == 64) {
                    firstStarted.complete(Unit)
                    try { awaitCancellation() }
                    finally {
                        withContext(NonCancellable) {
                            cleanupEntered.complete(Unit)
                            releaseCleanup.await()
                            cleanupFinished.complete(Unit)
                        }
                    }
                }
                if (height == 80) replacementLoaded.complete(Unit)
                return ReadingHistoryCoverResult(CoverImage.Static(green), false)
            }
        }
        fun awaitGate(gate: CompletableDeferred<Unit>) {
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame()
                gate.isCompleted
            }
        }
        fun assertCommittedImage() {
            val tag = "history-cover-${row().key}"
            compose.onNodeWithTag(tag, true).performScrollTo().assertIsDisplayed()
            // This fixture has no summary images. Require this row's actual Image child,
            // which disappears if an obsolete cleanup resets result to null.
            compose.onNode(
                hasContentDescription(row().identity.name) and hasAnyAncestor(hasTestTag(tag)),
                useUnmergedTree = true,
            ).assertExists().assertIsDisplayed()
        }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    LegadoComposeTheme { ReadingHistoryScreen(screenState, actions(), controlled) }
                }
            }
            awaitGate(firstStarted)
            compose.runOnIdle { density = 1.25f }
            awaitGate(cleanupEntered)
            awaitGate(replacementLoaded)
            assertCommittedImage()
            releaseCleanup.complete(Unit)
            awaitGate(cleanupFinished)
            // The cancelled load's cleanup must never erase a newer committed image.
            assertCommittedImage()
        } finally {
            releaseCleanup.complete(Unit)
        }
    }

}
