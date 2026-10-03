package io.legado.app.ui.components.markdown

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchableRichTextTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val noImages =
        object : MarkdownImageRepository {
            override suspend fun load(source: String, width: Int) = null
        }

    @Test
    fun actualHtmlRunsKeepColorsUnderlineStrikethroughEmphasisAndTextLayout() {
        val clicked = mutableListOf<String>()
        val document =
            projectTextDocument(
                "<p><span style='color:#f00;background-color:blue;text-decoration:underline line-through'><b>Colored</b></span> <a href='https://example.com/html'><i>Open</i></a></p>",
                "HTML",
            )
        val geometry = mutableListOf<RichTextGeometry>()
        compose.setContent {
            LegadoComposeTheme {
                SearchableRichText(
                    document,
                    emptyList(),
                    -1,
                    noImages,
                    { clicked += it },
                    {},
                    { geometry += it },
                    Modifier.width(360.dp),
                )
            }
        }
        val text =
            compose
                .onNodeWithText("Colored Open")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .single()
        assertTrue(
            text.spanStyles.any {
                it.item.color == Color.Red &&
                    it.item.background == Color.Blue &&
                    it.item.fontWeight == FontWeight.Bold &&
                    it.item.textDecoration?.contains(TextDecoration.LineThrough) == true
            }
        )
        compose.runOnIdle {
            assertTrue(geometry.isNotEmpty())
            assertTrue(
                geometry.last().layout.layoutInput.text.spanStyles.any {
                    it.item.fontWeight == FontWeight.Bold && it.item.color == Color.Red
                }
            )
        }
    }

    @Test
    fun standaloneHtmlLinkInvokesExactTargetAndTableHeaderAndCellsRemainVisible() {
        val clicked = mutableListOf<String>()
        val document =
            projectTextDocument(
                "<p><a href='https://example.com/html'>Open</a></p><table><tr><th>H1</th><th>H2</th></tr><tr><td>A</td><td>B</td></tr></table>",
                "HTML",
            )
        compose.setContent {
            LegadoComposeTheme {
                SearchableRichText(
                    document,
                    emptyList(),
                    -1,
                    noImages,
                    { clicked += it },
                    {},
                    {},
                    Modifier.width(360.dp),
                )
            }
        }
        compose.onNodeWithText("Open").performTouchInput { click(Offset(20f, center.y)) }
        listOf("H1", "H2", "A", "B").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.runOnIdle { assertEquals(listOf("https://example.com/html"), clicked) }
    }

    @Test
    fun htmlTextRetainsNativeSelectionAndClipboardCopy() {
        val toolbar = CaptureToolbar()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                LegadoComposeTheme {
                    SearchableRichText(
                        projectTextDocument("<strong>copyable</strong>", "HTML"),
                        emptyList(),
                        -1,
                        noImages,
                        {},
                        {},
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText("copyable").performTouchInput { longClick(Offset(30f, center.y)) }
        compose.waitUntil { toolbar.copy != null }
        compose.runOnIdle { toolbar.copy?.invoke() }
        compose.waitUntil { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copyable" }
    }

    @Test
    fun htmlImageDrawsActualPixelsLongPressInspectsSourceAndDisposalReleasesTheLease() {
        var document by
            mutableStateOf(
                projectTextDocument("<p><img src='image-source' alt='Picture'></p>", "HTML")
            )
        var released = 0
        val inspected = mutableListOf<String>()
        val bitmap =
            Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE)
            }
        val repository =
            object : MarkdownImageRepository {
                override suspend fun load(source: String, width: Int) =
                    AnimatedDrawableResource(BitmapDrawable(context.resources, bitmap)) {
                        released++
                    }
            }
        val tag = "rich-image-loaded-${document.leaves.single().id}-0"
        compose.setContent {
            LegadoComposeTheme {
                SearchableRichText(
                    document,
                    emptyList(),
                    -1,
                    repository,
                    {},
                    { inspected += it },
                    {},
                    Modifier.width(100.dp),
                )
            }
        }
        compose.waitUntil { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithTag(tag).assertContentDescriptionEquals("Picture")
        val pixels = node.captureToImage().toPixelMap()
        assertEquals(Color.Blue, pixels[pixels.width / 2, pixels.height / 2])
        node.performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(listOf("image-source"), inspected)
            document = projectTextDocument("Replacement", "TEXT")
        }
        compose.waitUntil { released == 1 }
        bitmap.recycle()
    }

    private class CaptureToolbar : TextToolbar {
        var copy: (() -> Unit)? = null
        override var status = TextToolbarStatus.Hidden

        override fun hide() {
            status = TextToolbarStatus.Hidden
        }

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            copy = onCopyRequested
            status = TextToolbarStatus.Shown
        }
    }
}
