package io.legado.app.ui.book.read.page

import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.ReaderInfoValues
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Draws the reader's formatted tip text through Compose while keeping Android span metrics. */
@Composable
internal fun ComposeReaderInfoText(
    template: String,
    values: ReaderInfoValues,
    color: Int,
    textSizeSp: Int,
    typeface: android.graphics.Typeface?,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    contentDescription: String? = null,
    onGeometry: ((androidx.compose.ui.geometry.Offset, Int) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val textSizePx = with(density) { textSizeSp.sp.toPx() }
    val horizontalPadding = with(density) { 10.dp.roundToPx() }
    val verticalPadding = with(density) { 6.dp.roundToPx() }
    val text = remember(template, values) { ReaderInfoTemplateRenderer.render(template, values) }
    val measuredText: CharSequence = if (text.isEmpty()) " " else text
    val paint =
        remember(color, textSizePx, typeface) {
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = textSizePx
                this.typeface = typeface
                isSubpixelText = true
            }
        }
    val accessibleText =
        remember(template, values) {
            ReaderInfoTemplate.parse(template, values).joinToString(separator = "") { part ->
                when (part) {
                    is io.legado.app.help.config.ReaderInfoPart.Text -> part.value
                    is io.legado.app.help.config.ReaderInfoPart.BatteryIcon -> "${part.level}%"
                }
            }
        }
    val description = contentDescription ?: accessibleText
    val modifierWithSemantics = modifier.semantics { this.contentDescription = description }
    val layoutCoordinatesModifier =
        if (onGeometry == null) {
            modifierWithSemantics
        } else {
            modifierWithSemantics.onGloballyPositioned { coordinates: LayoutCoordinates ->
                val innerWidth = (coordinates.size.width - horizontalPadding).coerceAtLeast(1)
                val textLayout = createReaderInfoLayout(measuredText, paint, innerWidth)
                onGeometry(
                    coordinates.positionInRoot(),
                    with(density) { 3.dp.roundToPx() } + textLayout.getLineBaseline(0),
                )
            }
        }

    Layout(
        content = {},
        modifier =
            layoutCoordinatesModifier.drawIntoReaderInfo(
                text,
                paint,
                horizontalPadding,
            ),
        measurePolicy =
            MeasurePolicy { _, constraints ->
                val desiredTextWidth =
                    ceil(StaticLayout.getDesiredWidth(text, paint).toDouble()).toInt()
                val desiredWidth = desiredTextWidth + horizontalPadding
                val width =
                    if (fillWidth) {
                        constraints.maxWidth
                    } else {
                        desiredWidth
                            .coerceIn(constraints.minWidth, constraints.maxWidth)
                            .coerceAtMost(constraints.maxWidth)
                    }
                val contentWidth = (width - horizontalPadding).coerceAtLeast(1)
                val textLayout = createReaderInfoLayout(measuredText, paint, contentWidth)
                val textHeight = textLayout.height
                val height =
                    (textHeight + verticalPadding).coerceIn(
                        constraints.minHeight,
                        constraints.maxHeight,
                    )
                layout(
                    width,
                    height,
                    alignmentLines =
                        mapOf(FirstBaseline to (3.dp.roundToPx() + textLayout.getLineBaseline(0))),
                ) {}
            },
    )
}

private fun Modifier.drawIntoReaderInfo(
    text: CharSequence,
    paint: TextPaint,
    horizontalPadding: Int,
): Modifier =
    androidx.compose.ui.draw.drawWithContent {
        drawContent()
        if (text.isEmpty()) return@drawWithContent
        val layout =
            createReaderInfoLayout(
                text,
                paint,
                (size.width.roundToInt() - horizontalPadding).coerceAtLeast(1),
            )
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            nativeCanvas.save()
            nativeCanvas.translate(4.dp.toPx(), 3.dp.toPx())
            layout.draw(nativeCanvas)
            nativeCanvas.restore()
        }
    }

private fun createReaderInfoLayout(
    text: CharSequence,
    paint: TextPaint,
    width: Int,
): StaticLayout {
    val builder =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(1)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        builder.setUseLineSpacingFromFallbacks(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
    }
    return builder.build()
}
