package io.legado.app.ui.book.read.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.ReaderInfoValues

@Composable
internal fun ReaderPageChrome(
    statusBarHeight: Int,
    navigationBarHeight: Int,
    showStatusBar: Boolean,
    showNavigationBar: Boolean,
    showHeader: Boolean,
    showFooter: Boolean,
    showHeaderLine: Boolean,
    showFooterLine: Boolean,
    headerPadding: ReaderTipPadding,
    footerPadding: ReaderTipPadding,
    headerTemplates: List<String>,
    footerTemplates: List<String>,
    values: ReaderInfoValues,
    tipColor: Int,
    dividerColor: Int,
    accentColor: Int,
    textSizeSp: Int,
    typeface: android.graphics.Typeface?,
    bookmarkVisible: Boolean,
    bookmarkInHeader: Boolean,
    bookmarkOffset: IntOffset,
    bookmarkDescription: String,
    onContentBounds: (IntRect) -> Unit,
    onHeaderMeasured: (Int) -> Unit,
    onHeaderRightGeometry: (Offset, Int) -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (showStatusBar)
                    Spacer(Modifier.fillMaxWidth().height(with(density) { statusBarHeight.toDp() }))
                if (showHeader) {
                    ReaderTipBand(
                        templates = headerTemplates,
                        values = values,
                        padding = headerPadding,
                        color = tipColor,
                        textSizeSp = textSizeSp,
                        typeface = typeface,
                        bookmarkInHeader = bookmarkInHeader,
                        bookmarkDescription = bookmarkDescription,
                        onRightGeometry = onHeaderRightGeometry,
                        modifier =
                            Modifier.fillMaxWidth().onGloballyPositioned {
                                coordinates: LayoutCoordinates ->
                                onHeaderMeasured(coordinates.size.height)
                            },
                    )
                }
                if (showHeaderLine && showHeader) {
                    Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(Color(dividerColor)))
                }
                Spacer(
                    Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInRoot()
                        onContentBounds(
                            IntRect(
                                position.x.toInt(),
                                position.y.toInt(),
                                position.x.toInt() + coordinates.size.width,
                                position.y.toInt() + coordinates.size.height,
                            )
                        )
                    }
                )
                if (showFooterLine && showFooter) {
                    Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(Color(dividerColor)))
                }
                if (showFooter) {
                    ReaderTipBand(
                        templates = footerTemplates,
                        values = values,
                        padding = footerPadding,
                        color = tipColor,
                        textSizeSp = textSizeSp,
                        typeface = typeface,
                        bookmarkInHeader = false,
                        bookmarkDescription = bookmarkDescription,
                        onRightGeometry = null,
                        modifier = Modifier.fillMaxWidth(),
                        footer = true,
                    )
                }
                if (showNavigationBar)
                    Spacer(
                        Modifier.fillMaxWidth().height(with(density) { navigationBarHeight.toDp() })
                    )
            }
            if (bookmarkVisible) {
                BookmarkPageIndicator(
                    inHeader = bookmarkInHeader,
                    accentColor = accentColor,
                    description = bookmarkDescription,
                    modifier = Modifier.align(Alignment.TopEnd).offset { bookmarkOffset },
                )
            }
        }
    }
}

internal data class ReaderTipPadding(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Composable
private fun ReaderTipBand(
    templates: List<String>,
    values: ReaderInfoValues,
    padding: ReaderTipPadding,
    color: Int,
    textSizeSp: Int,
    typeface: android.graphics.Typeface?,
    bookmarkInHeader: Boolean,
    bookmarkDescription: String,
    onRightGeometry: ((Offset, Int) -> Unit)?,
    modifier: Modifier,
    footer: Boolean = false,
) {
    val density = LocalDensity.current
    val padLeft = with(density) { padding.left.dp }
    val padTop = with(density) { padding.top.dp }
    val padRight = with(density) { padding.right.dp }
    val padBottom = with(density) { padding.bottom.dp }
    val rightTemplate = if (bookmarkInHeader) " " else templates[2]

    Box(
        modifier.absolutePadding(left = padLeft, top = padTop, right = padRight, bottom = padBottom)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val leftTemplate = templates[0]
            if (leftTemplate.isNotEmpty() || footer) {
                ComposeReaderInfoText(
                    template = leftTemplate,
                    values = values,
                    color = color,
                    textSizeSp = textSizeSp,
                    typeface = typeface,
                    modifier =
                        Modifier.weight(1f)
                            .then(
                                if (leftTemplate.isEmpty())
                                    Modifier.alpha(0f).clearAndSetSemantics {}
                                else Modifier
                            ),
                    fillWidth = true,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (rightTemplate.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                ComposeReaderInfoText(
                    template = rightTemplate,
                    values = values,
                    color = color,
                    textSizeSp = textSizeSp,
                    typeface = typeface,
                    modifier =
                        Modifier.then(
                            if (bookmarkInHeader) Modifier.widthIn(min = 32.dp) else Modifier
                        ),
                    contentDescription = if (bookmarkInHeader) bookmarkDescription else null,
                    onGeometry = onRightGeometry,
                )
            }
        }
        val middleTemplate = templates[1]
        if (middleTemplate.isNotEmpty()) {
            ComposeReaderInfoText(
                template = middleTemplate,
                values = values,
                color = color,
                textSizeSp = textSizeSp,
                typeface = typeface,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun BoxScope.BookmarkPageIndicator(
    inHeader: Boolean,
    accentColor: Int,
    description: String,
    modifier: Modifier = Modifier,
) {
    val iconSize = if (inHeader) 32.dp else 20.dp
    val iconHeight = if (inHeader) 32.dp else 40.dp
    val painter =
        painterResource(
            if (inHeader) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_long
        )
    val tint = remember(accentColor) { Color(accentColor) }
    Box(modifier = modifier.size(iconSize, iconHeight)) {
        Image(
            painter = painter,
            contentDescription = if (inHeader) null else description,
            colorFilter = ColorFilter.tint(tint),
            modifier =
                Modifier.fillMaxSize()
                    .then(if (inHeader) Modifier.padding(4.dp) else Modifier)
                    .alpha(0.88f),
        )
    }
}
