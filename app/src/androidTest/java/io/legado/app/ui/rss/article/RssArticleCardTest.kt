package io.legado.app.ui.rss.article

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class RssArticleCardTest {
    @get:Rule val compose = createComposeRule()
    private var row by mutableStateOf(RssArticleRow("owned", "Title", "image", "Date", false, "source"))
    private var style by mutableIntStateOf(0)
    private var landscape by mutableStateOf(false)
    private fun show(click: () -> Unit = {}, image: @androidx.compose.runtime.Composable (Modifier, Boolean, Boolean) -> Unit = { modifier, _, _ -> Box(modifier.testTag("image-slot")) }) {
        compose.setContent { LegadoComposeTheme { RssArticleCard(row, style, landscape, click, Modifier.width(300.dp), image) } }
    }
    private fun text(field: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("rss-article-$field-owned", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
    @Test fun allFiveStylesKeepTitlesDatesAndExactClickIdentity() {
        var clicks = 0; show({ clicks++ })
        repeat(5) { value ->
            compose.runOnIdle { style = value }
            compose.onNodeWithTag("rss-article-title-owned", true).assertTextEquals("Title")
            compose.onNodeWithTag("rss-article-date-owned", true).assertTextEquals("Date")
            compose.onNodeWithTag("rss-article-row-owned").performClick()
        }
        assertEquals(5, clicks)
    }
    @Test fun listAndGridImageFramesRetainOriginalSizes() {
        show(); compose.onNodeWithTag("rss-article-row-owned").assertHeightIsEqualTo(100.dp)
        compose.onNodeWithTag("image-slot").assertHeightIsEqualTo(68.dp)
        compose.runOnIdle { style = 2 }; compose.onNodeWithTag("image-slot").assertHeightIsEqualTo(272.dp)
        compose.runOnIdle { style = 4 }; compose.onNodeWithTag("image-slot").assertHeightIsEqualTo(182.dp)
    }
    @Test fun waterfallPortraitAndLandscapeKeepTheirIndependentTypographyAndLineLimits() {
        style = 3; show()
        assertEquals(13f, text("title").layoutInput.style.fontSize.value); assertEquals(9, text("title").layoutInput.maxLines)
        assertEquals(11f, text("date").layoutInput.style.fontSize.value); assertEquals(39, text("date").layoutInput.maxLines)
        compose.runOnIdle { landscape = true }
        assertEquals(16f, text("title").layoutInput.style.fontSize.value); assertEquals(5, text("title").layoutInput.maxLines)
        assertEquals(14f, text("date").layoutInput.style.fontSize.value); assertEquals(19, text("date").layoutInput.maxLines)
    }
    @Test fun updatingSameIdentityClearsStaleTitleDateAndReadColorInEveryStyle() {
        show(); val unread = text("title").layoutInput.style.color
        compose.runOnIdle { row = row.copy(title = "Latest", pubDate = null, read = true) }
        compose.onNodeWithTag("rss-article-title-owned", true).assertTextEquals("Latest")
        compose.onNodeWithTag("rss-article-date-owned", true).assertTextEquals("")
        assertNotEquals(unread, text("title").layoutInput.style.color)
    }
    private class Picture : Drawable(), Animatable {
        var starts = 0; var stops = 0; var running = false
        override fun getIntrinsicWidth() = 100
        override fun getIntrinsicHeight() = 200
        override fun draw(canvas: Canvas) { canvas.drawColor(android.graphics.Color.RED) }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated") override fun getOpacity() = PixelFormat.OPAQUE
        override fun start() { starts++; running = true }
        override fun stop() { stops++; running = false }
        override fun isRunning() = running
    }
    private class Images : RssArticleImageRepository {
        val picture = Picture(); var clears = 0; var loads = 0; var sourceOrigin = ""; var natural = false
        var fail = false; var cached: Float? = 2f
        override suspend fun ratio(source: String) = cached
        override suspend fun load(source: String, origin: String, width: Int, height: Int, natural: Boolean): AnimatedDrawableResource? {
            loads++; sourceOrigin = origin; this.natural = natural
            return if (fail) null else AnimatedDrawableResource(picture) { clears++ }
        }
    }
    @Test fun naturalImagePreservesRatioSourceOriginAndDoesNotReloadWhenItsHeightChanges() {
        val images = Images(); compose.setContent { RssArticleImage(row, images, Modifier.width(120.dp), natural = true) }
        compose.waitUntil { images.loads == 1 }
        compose.onNodeWithTag("rss-article-image-owned").assertHeightIsEqualTo(240.dp)
        assertEquals("source", images.sourceOrigin); assertTrue(images.natural)
        compose.waitForIdle(); assertEquals(1, images.loads)
    }
    @Test fun animationOnlyRunsOnVisibleResumedPageAndDisposalReleasesOnce() {
        val images = Images(); val owner = object : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
        var visible by mutableStateOf(true)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { if (visible) RssArticleImage(row, images, Modifier.size(100.dp)) } }
        compose.waitUntil { images.loads == 1 }; assertFalse(images.picture.running)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; assertTrue(images.picture.running)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }; assertFalse(images.picture.running)
        compose.runOnIdle { visible = false }; compose.waitUntil { images.clears == 1 }; assertNull(images.picture.callback)
        compose.waitForIdle(); assertEquals(1, images.clears)
    }
    @Test fun failedOrAbsentListImageHidesButGridKeepsItsTransparentFrame() {
        val images = Images(); images.fail = true
        var grid by mutableStateOf(false)
        compose.setContent { RssArticleImage(row, images, Modifier.size(100.dp), keepEmpty = grid) }
        compose.waitUntil { images.loads == 1 }; compose.onNodeWithTag("rss-article-image-owned").assertDoesNotExist()
        compose.runOnIdle { grid = true }; compose.onNodeWithTag("rss-article-image-owned").assertExists()
        compose.runOnIdle { row = row.copy(image = null) }; compose.onNodeWithTag("rss-article-image-owned").assertExists()
    }
}
