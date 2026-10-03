package io.legado.app.ui.book.search

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.BookSearchResult
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Actual layout counterparts of the retired, last-consumer item_search XML contracts. */
class SearchResultComposeLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val result =
        BookSearchResult.from(
            SearchBook(
                    bookUrl = "fixture",
                    origin = "source",
                    name = "Title",
                    author = "Author",
                    intro = "Long introduction ".repeat(70),
                    kind = "First long label,Second long label,Third long label",
                    latestChapterTitle = "Newest chapter",
                    wordCount = "Many words",
                )
                .apply {
                    origins.add("one")
                    origins.add("two")
                }
        )

    private fun show(direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            LegadoComposeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Surface(Modifier.width(300.dp)) {
                        BookSearchResultRow(
                            result,
                            onShelf = true,
                            hasRead = false,
                            loadOnlyWifi = false,
                            onClick = {},
                            cover = { Spacer(it) },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun metadataRowsHaveSeparateVerticalSpace() {
        show()
        val title = bounds("search-title-${result.id}")
        val author =
            compose
                .onNodeWithText(
                    context.getString(R.string.author_show, result.author),
                    useUnmergedTree = true,
                )
                .fetchSemanticsNode()
                .boundsInRoot
        val labels = bounds("search-labels-${result.id}")
        val latest =
            compose
                .onNodeWithText(
                    context.getString(R.string.lasted_show, result.latestChapterTitle),
                    useUnmergedTree = true,
                )
                .fetchSemanticsNode()
                .boundsInRoot
        val intro = bounds("search-intro-${result.id}")
        assertTrue(title.bottom <= author.top)
        assertTrue(author.bottom <= labels.top)
        assertTrue(labels.bottom <= latest.top)
        assertTrue(latest.bottom <= intro.top)
    }

    @Test
    fun rtlPlacesCoverAtLogicalStartAndOriginCountAtLogicalEnd() {
        show(LayoutDirection.Rtl)
        val cover = bounds("search-cover-${result.id}")
        val title = bounds("search-title-${result.id}")
        val count = bounds("search-origin-count-${result.id}")
        assertTrue(cover.left >= title.right)
        assertTrue(count.right <= title.left)
    }

    @Test
    fun titleKeepsShelfMarkerBeforeNameAndCountAfterName() {
        show()
        val marker = bounds("search-shelf-marker-${result.id}")
        val title = bounds("search-title-${result.id}")
        val count = bounds("search-origin-count-${result.id}")
        assertTrue(marker.right <= title.left)
        assertTrue(title.right <= count.left)
    }

    @Test
    fun growingMetadataKeepsCoverVerticallyCentered() {
        show()
        val row = bounds("search-result-${result.id}")
        val cover = bounds("search-cover-${result.id}")
        assertTrue(row.height > cover.height)
        assertTrue(abs(row.center.y - cover.center.y) < 2f)
    }

    @Test
    fun labelsWrapWithinTheActualAvailableMetadataWidth() {
        show()
        val area = bounds("search-labels-${result.id}")
        val first =
            compose
                .onNodeWithText("First long label", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val last =
            compose
                .onNodeWithText("Third long label", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(last.top > first.top)
        assertTrue(first.left >= area.left && first.right <= area.right)
        assertTrue(last.left >= area.left && last.right <= area.right)
    }

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
}
