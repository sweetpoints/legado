package io.legado.app.ui.book.info.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.BookDetailBook
import io.legado.app.data.repository.BookDetailChapter
import io.legado.app.data.repository.BookDetailData
import io.legado.app.data.repository.BookDetailIdentity
import io.legado.app.data.repository.BookDetailPreferences
import io.legado.app.data.repository.BookDetailSession
import io.legado.app.data.repository.BookDetailSource
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val preferences = BookDetailPreferences(true, false, false, false)

    private fun initial(webFile: Boolean = false): BookDetailState {
        val book =
            Book(
                bookUrl = "book",
                name = "Name",
                author = "Author",
                origin = "source",
                durChapterTitle = "Current",
                durChapterIndex = 0,
                totalChapterNum = 2,
                type =
                    if (webFile) io.legado.app.constant.BookType.webFile
                    else io.legado.app.constant.BookType.text,
            )
        val data =
            BookDetailData(
                BookDetailBook.from(book),
                BookDetailSource.from(
                    BookSource(
                        bookSourceUrl = "source",
                        bookSourceName = "Source",
                        loginUrl = "https://login",
                    )
                ),
                listOf(BookDetailChapter("{}", 0, "chapter", "Fallback", false)),
                listOf("Group"),
                listOf("Kind"),
                true,
            )
        return BookDetailState(
            BookDetailSession(BookDetailIdentity(bookUrl = "book"), data),
            loading = false,
            loaded = true,
        )
    }

    @Test
    fun visibleShelfReadTocAndKindInteractionsRetainDistinctActionAndLongClickContracts() {
        val actions = mutableListOf<BookDetailAction>()
        val clicks = mutableListOf<Triple<BookDetailClick, String?, Boolean>>()
        compose.setContent {
            LegadoComposeTheme {
                BookDetailScreen(
                    initial(),
                    preferences,
                    BookDetailActions(
                        action = { actions += it },
                        click = { kind, value, long -> clicks += Triple(kind, value, long) },
                    ),
                    cover = { _, layout -> Box(layout) },
                    intro = { _, _, _, _ -> },
                )
            }
        }
        compose.onNodeWithTag("book-detail-shelf").performClick()
        compose.onNodeWithTag("book-detail-read").performClick()
        compose.onNodeWithTag("book-detail-toc").performScrollTo().performClick()
        compose.onNodeWithTag("book-detail-kind-0").performScrollTo().performTouchInput {
            longClick()
        }
        assertEquals(
            listOf(BookDetailAction.Shelf, BookDetailAction.Read, BookDetailAction.Toc),
            actions,
        )
        assertEquals(Triple(BookDetailClick.Kind, "Kind", true), clicks.single())
        compose.onNodeWithTag("book-detail-toc").assertTextContains("Current", substring = true)
    }

    @Test
    fun darkSmallScreenKeepsBottomControlsVisibleAndScrollableMetadataUsesThemeContentColor() {
        var density = 1f
        var content: Color? = null
        compose.setContent {
            density = LocalDensity.current.density
            LegadoComposeTheme {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.size(320.dp, 360.dp)) {
                        BookDetailScreen(
                            initial(),
                            preferences,
                            BookDetailActions(),
                            cover = { _, layout -> Box(layout) },
                            intro = { _, _, _, layout ->
                                content = LocalContentColor.current
                                Text("Intro", layout)
                            },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("book-detail-group").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("book-detail-read").assertIsDisplayed()
        listOf("edit", "share", "more", "shelf", "read", "refresh-toc").forEach { tag ->
            if (tag == "refresh-toc") compose.onNodeWithTag("book-detail-$tag").performScrollTo()
            val bounds = compose.onNodeWithTag("book-detail-$tag").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= 48 * density - 1)
        }
        compose.onNodeWithText("Intro").performScrollTo().assertIsDisplayed()
        assertEquals(darkColorScheme().onSurface, content)
    }

    @Test
    fun kindLabelsWrapIntoCenteredRowsWithinSmallScreenWidth() {
        val original = initial()
        val labels =
            listOf("First long label", "Second long label", "Third long label", "Fourth long label")
        val data = checkNotNull(original.data).copy(kinds = labels)
        val state = original.copy(session = checkNotNull(original.session).copy(data = data))
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            LegadoComposeTheme {
                Box(Modifier.size(320.dp, 700.dp)) {
                    BookDetailScreen(
                        state,
                        preferences,
                        BookDetailActions(),
                        cover = { _, layout -> Box(layout) },
                        intro = { _, _, _, _ -> },
                    )
                }
            }
        }
        val bounds =
            labels.indices.map { index ->
                compose.onNodeWithTag("book-detail-kind-$index").fetchSemanticsNode().boundsInRoot
            }
        val rows = bounds.groupBy { it.top }
        assertTrue(rows.size > 1)
        assertTrue(bounds.all { it.left >= 0 && it.right <= 320 * density + 1 })
        for (row in rows.values) {
            val center = (row.minOf { it.left } + row.maxOf { it.right }) / 2
            assertTrue(abs(center - 160 * density) <= 2)
        }
    }

    @Test
    fun sourceMenuCheckboxAndExpandedMenuRestoreWithoutChangingUnderlyingBookAndWebFilesHideToc() {
        val tester = StateRestorationTester(compose)
        val actions = mutableListOf<BookDetailAction>()
        compose.setContent {
            LegadoComposeTheme {
                BookDetailScreen(
                    initial(true),
                    preferences,
                    BookDetailActions(action = { actions += it }),
                    cover = { _, layout -> Box(layout) },
                    intro = { _, _, _, _ -> },
                )
            }
        }
        compose.onNodeWithTag("book-detail-toc").assertDoesNotExist()
        compose.onNodeWithTag("book-detail-more").performClick()
        tester.emulateSavedInstanceStateRestore()
        compose
            .onNodeWithTag("book-detail-menu-${R.string.allow_update}")
            .assertIsDisplayed()
            .performClick()
        assertEquals(listOf(BookDetailAction.CanUpdate), actions)
    }
}
