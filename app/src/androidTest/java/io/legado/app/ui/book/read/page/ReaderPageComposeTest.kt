package io.legado.app.ui.book.read.page

import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.ReaderInfoValues
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderPageComposeTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun readerInfoCanvasMeasuresAndDrawsBatterySpan() {
        var position = Offset.Zero
        var baseline = 0
        compose.setContent {
            ComposeReaderInfoText(
                template = "{时间} {电量图标}",
                values = ReaderInfoValues(time = "12:34", battery = 75),
                color = AndroidColor.BLACK,
                textSizeSp = 12,
                typeface = Typeface.DEFAULT,
                modifier = Modifier.width(140.dp).testTag("reader-info-canvas"),
                onGeometry = { top, textBaseline ->
                    position = top
                    baseline = textBaseline
                },
            )
        }

        val image = compose.onNodeWithTag("reader-info-canvas").captureToImage()
        compose.onNodeWithContentDescription("12:34 75%").assertExists()
        val pixels = image.toPixelMap()
        assertTrue(image.width > 0)
        assertTrue(image.height > 0)
        assertTrue(
            (0 until pixels.width).any { x ->
                (0 until pixels.height).any { y ->
                    pixels[x, y].alpha > 0.8f && pixels[x, y].red < 0.2f
                }
            }
        )
        compose.runOnIdle {
            assertTrue(position.y >= 0f)
            assertTrue(baseline > 0)
            assertTrue(baseline <= image.height)
        }
    }

    @Test
    fun chromeMeasuresReaderSlotsAndSnapshotIncludesDividerAndBookmark() {
        val bounds = mutableStateOf(IntRect.Zero)
        val headerHeight = mutableStateOf(0)
        val baseline = mutableStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            Box(Modifier.requiredSize(320.dp, 200.dp).testTag("reader-page-preview")) {
                ReaderPageChrome(
                    statusBarHeight = with(density) { 20.dp.roundToPx() },
                    navigationBarHeight = with(density) { 16.dp.roundToPx() },
                    showStatusBar = true,
                    showNavigationBar = true,
                    showHeader = true,
                    showFooter = true,
                    showHeaderLine = true,
                    showFooterLine = true,
                    headerPadding = ReaderTipPadding(8, 4, 8, 4),
                    footerPadding = ReaderTipPadding(8, 4, 8, 4),
                    headerTemplates = listOf("{书名}", "{章节}", "{时间}"),
                    footerTemplates = listOf("{页码}/{总页数}", "", "{阅读进度}"),
                    values =
                        ReaderInfoValues(
                            bookName = "Reader",
                            chapterTitle = "Chapter",
                            time = "12:34",
                            battery = 75,
                            page = "2",
                            totalPages = "10",
                            readProgress = "20%",
                        ),
                    tipColor = AndroidColor.BLACK,
                    dividerColor = AndroidColor.MAGENTA,
                    accentColor = AndroidColor.BLUE,
                    textSizeSp = 12,
                    typeface = Typeface.DEFAULT,
                    bookmarkVisible = true,
                    bookmarkInHeader = true,
                    bookmarkOffset = IntOffset.Zero,
                    bookmarkDescription = "Bookmark",
                    onContentBounds = { bounds.value = it },
                    onHeaderMeasured = { headerHeight.value = it },
                    onHeaderRightGeometry = { _, textBaseline -> baseline.value = textBaseline },
                )
            }
        }

        compose
            .onNodeWithTag("reader-page-preview")
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(200.dp)
        val image = compose.onNodeWithTag("reader-page-preview").captureToImage()
        val pixels = image.toPixelMap()
        val magentaPixelCount =
            (0 until pixels.width).sumOf { x ->
                (0 until pixels.height).count { y ->
                    val pixel = pixels[x, y]
                    pixel.alpha > 0.8f &&
                        pixel.red > 0.8f &&
                        pixel.blue > 0.8f &&
                        pixel.green < 0.2f
                }
            }
        compose.runOnIdle {
            assertTrue(headerHeight.value > 0)
            assertTrue(baseline.value > 0)
            assertTrue(bounds.value.width > 0)
            assertTrue(bounds.value.height > 0)
            assertTrue(bounds.value.top > 0)
            assertTrue(bounds.value.bottom < image.height)
            assertTrue(magentaPixelCount > 0)
        }
    }
}
