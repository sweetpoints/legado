package io.legado.app.ui.components.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class ComposeMarkdownTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val noImages = object : MarkdownImageRepository { override suspend fun load(source: String, width: Int) = null }
    @Test fun boldAndCodeReachTextLayoutAndLinkClicksKeepTheExactTarget() {
        val clicked = mutableListOf<String>()
        compose.setContent { LegadoComposeTheme { ComposeMarkdown(parseMarkdownDocument("**Bold** `Code`\n\n[Open](https://example.com/path)"), noImages, { clicked += it }) } }
        val text = compose.onNodeWithTag("markdown-text-root-0-0").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && text.text.substring(it.start, it.end) == "Bold" })
        assertTrue(text.spanStyles.any { it.item.fontFamily == androidx.compose.ui.text.font.FontFamily.Monospace && text.text.substring(it.start, it.end) == "Code" })
        compose.onNodeWithText("Open").performTouchInput { click(Offset(20f, center.y)) }
        compose.runOnIdle { assertEquals(listOf("https://example.com/path"), clicked) }
    }
    @Test fun tableHeadersBodyAndAlignmentAreRenderedAsSelectableText() {
        compose.setContent { LegadoComposeTheme { ComposeMarkdown(parseMarkdownDocument("| Left | Right |\n| :--- | ---: |\n| A | B |"), noImages, {}, Modifier.width(360.dp)) } }
        listOf("Left", "Right", "A", "B").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithTag("markdown-table-root-0").assertExists()
    }
    @Test fun selectedMarkdownTextCanBeCopiedToTheRealClipboard() {
        val toolbar = CaptureToolbar(); val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.setContent { CompositionLocalProvider(LocalTextToolbar provides toolbar) { LegadoComposeTheme {
            ComposeMarkdown(parseMarkdownDocument("copyable"), noImages, {})
        } } }
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("test", "baseline")) }
        compose.onNodeWithText("copyable").performTouchInput { longClick(Offset(30f, center.y)) }
        compose.waitUntil { toolbar.copy != null }; compose.runOnIdle { toolbar.copy?.invoke() }
        compose.waitUntil { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copyable" }
    }
    @Test fun actualGlideFileImageDrawsPixelsAtItsOriginalAspectRatio() {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val file = File.createTempFile("memo-image-", ".png", context.cacheDir)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val repository = GlideMarkdownImageRepository(context)
            compose.setContent { LegadoComposeTheme { ComposeMarkdown(parseMarkdownDocument("![Red picture](${file.toURI()})"), repository, {}, Modifier.width(200.dp)) } }
            compose.waitUntil { compose.onAllNodesWithTag("markdown-loaded-root-0-0").fetchSemanticsNodes().isNotEmpty() }
            val node = compose.onNodeWithTag("markdown-loaded-root-0-0").assertContentDescriptionEquals("Red picture")
            val bounds = node.fetchSemanticsNode().boundsInRoot; assertEquals(2f, bounds.width / bounds.height, .05f)
            val pixels = node.captureToImage().toPixelMap(); assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
        } finally { file.delete(); if (!bitmap.isRecycled) bitmap.recycle() }
    }
    @Test fun imageLeaseIsReleasedOnceWhenMarkdownChangesAndLinkedImageOpensTarget() {
        var content by mutableStateOf("[![Picture](memory-image)](https://example.com/image)"); var released = 0
        val targets = mutableListOf<String>(); val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val repository = object : MarkdownImageRepository { override suspend fun load(source: String, width: Int) =
            AnimatedDrawableResource(BitmapDrawable(context.resources, bitmap)) { released++ } }
        compose.setContent { LegadoComposeTheme { ComposeMarkdown(parseMarkdownDocument(content), repository, { targets += it }, Modifier.width(100.dp)) } }
        compose.waitUntil { compose.onAllNodesWithTag("markdown-loaded-root-0-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("markdown-loaded-root-0-0").performClick()
        compose.runOnIdle { assertEquals(listOf("https://example.com/image"), targets); content = "Replacement text" }
        compose.waitUntil { released == 1 }; compose.runOnIdle { assertEquals(1, released) }; bitmap.recycle()
    }
    private class CaptureToolbar : TextToolbar {
        var copy: (() -> Unit)? = null; override var status = TextToolbarStatus.Hidden
        override fun hide() { status = TextToolbarStatus.Hidden }
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copy = onCopyRequested; status = TextToolbarStatus.Shown
        }
    }
}
