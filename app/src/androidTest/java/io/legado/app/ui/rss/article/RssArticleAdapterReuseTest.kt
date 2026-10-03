package io.legado.app.ui.rss.article

import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

/**
 * Recomposition now replaces RecyclerView reuse; verify ownership instead of binding source
 * strings.
 */
class RssArticleAdapterReuseTest {
    @get:Rule val compose = createComposeRule()
    private var row by
        mutableStateOf(RssArticleRow("owned", "Before", "before-image", "Date", false, "source"))
    private val loaded = mutableListOf<String>()
    private val cleared = mutableListOf<String>()
    private val images =
        object : RssArticleImageRepository {
            override suspend fun ratio(source: String): Float? = null

            override suspend fun load(
                source: String,
                origin: String,
                width: Int,
                height: Int,
                natural: Boolean,
            ): AnimatedDrawableResource {
                loaded += source
                return AnimatedDrawableResource(ColorDrawable(android.graphics.Color.RED)) {
                    cleared += source
                }
            }
        }

    private fun show() {
        compose.setContent {
            LegadoComposeTheme {
                RssArticleCard(
                    row,
                    0,
                    false,
                    {},
                    Modifier.width(320.dp),
                    image = { mod, natural, keep ->
                        RssArticleImage(row, images, mod, natural, keep)
                    },
                )
            }
        }
    }

    @Test
    fun emptyImageRemovesPreviousDrawableAndReleasesItsLeaseExactlyOnce() {
        show()
        compose.waitForIdle()
        assertEquals(listOf("before-image"), loaded)
        compose.runOnIdle { row = row.copy(image = null) }
        compose.onNodeWithTag("rss-article-image-owned").assertDoesNotExist()
        assertEquals(listOf("before-image"), cleared)
    }

    @Test
    fun imageChangeLoadsNewSourceWhileTitleAndReadChangesReuseItsExistingLease() {
        show()
        compose.waitForIdle()
        compose.runOnIdle {
            row = row.copy(image = "after-image", title = "After", read = true, pubDate = null)
        }
        compose.waitForIdle()
        assertEquals(listOf("before-image", "after-image"), loaded)
        assertEquals(listOf("before-image"), cleared)
        compose.onNodeWithTag("rss-article-title-owned", true).assertTextEquals("After")
        compose.onNodeWithTag("rss-article-date-owned", true).assertTextEquals("")
        compose.runOnIdle { row = row.copy(title = "Latest", read = false) }
        compose.waitForIdle()
        assertEquals(2, loaded.size)
        assertEquals(1, cleared.size)
    }
}
