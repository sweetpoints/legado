package io.legado.app.ui.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import io.legado.app.data.entities.rule.FlexChildStyle
import io.legado.app.data.repository.SourceLoginRow
import kotlin.math.roundToInt

/** Wrap first, then distribute remaining row width by flexGrow, as the legacy flexbox did. */
internal fun sourceLoginFlexLines(
    width: Int,
    natural: List<Int>,
    styles: List<FlexChildStyle>,
): List<List<Pair<Int, Int>>> {
    val lines = mutableListOf<MutableList<Pair<Int, Int>>>()
    natural.forEachIndexed { index, intrinsic ->
        val style = styles[index]
        val requested =
            (if (style.layout_flexBasisPercent >= 0)
                    (width * style.layout_flexBasisPercent).roundToInt()
                else intrinsic)
                .coerceAtLeast(0)
        val size = if (style.layout_flexShrink > 0) requested.coerceAtMost(width) else requested
        val line = lines.lastOrNull()
        if (
            line == null ||
                style.layout_wrapBefore && line.isNotEmpty() ||
                line.sumOf { it.second } + size > width
        )
            lines += mutableListOf(index to size)
        else line += index to size
    }
    return lines.map { line ->
        val remaining = (width - line.sumOf { it.second }).coerceAtLeast(0)
        val grow = line.sumOf { styles[it.first].layout_flexGrow.coerceAtLeast(0f).toDouble() }
        var assigned = 0
        line.mapIndexed { position, (index, base) ->
            val extra =
                if (grow == 0.0) 0
                else if (position == line.lastIndex && styles[index].layout_flexGrow > 0)
                    remaining - assigned
                else (remaining * styles[index].layout_flexGrow.coerceAtLeast(0f) / grow).toInt()
            assigned += extra
            index to (base + extra)
        }
    }
}

@Composable
internal fun SourceLoginFormLayout(
    rows: List<SourceLoginRow>,
    modifier: Modifier = Modifier,
    content: @Composable (SourceLoginRow) -> Unit,
) {
    Layout(
        content = { rows.forEachIndexed { index, row -> key(row.key, index) { content(row) } } },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val natural = measurables.mapIndexed { index, measurable ->
            val row = rows[index]
            if (row.type == "text" || row.type == "password" || row.type == "select" && row.modern)
                width
            else
                runCatching { measurable.maxIntrinsicWidth(Constraints.Infinity) }
                    .getOrDefault(width)
                    .coerceAtMost(width)
        }
        val lines = sourceLoginFlexLines(width, natural, rows.map { it.style })
        val layouts = lines.map { line ->
            val stretchHeight =
                line.maxOfOrNull { (index, itemWidth) ->
                    runCatching { measurables[index].maxIntrinsicHeight(itemWidth) }.getOrDefault(0)
                } ?: 0
            line.map { (index, itemWidth) ->
                val minimum =
                    if (rows[index].style.layout_alignSelf == "stretch") stretchHeight else 0
                index to
                    measurables[index].measure(
                        Constraints(itemWidth, itemWidth, minimum, Constraints.Infinity)
                    )
            }
        }
        val height = layouts.sumOf { line -> line.maxOfOrNull { it.second.height } ?: 0 }
        layout(width, constraints.constrainHeight(height)) {
            var y = 0
            layouts.forEach { line ->
                val lineHeight = line.maxOfOrNull { it.second.height } ?: 0
                val baseline =
                    line.maxOfOrNull {
                        it.second[FirstBaseline].takeUnless { value ->
                            value == androidx.compose.ui.layout.AlignmentLine.Unspecified
                        } ?: 0
                    } ?: 0
                var x = 0
                line.forEach { (index, placeable) ->
                    val offset =
                        when (rows[index].style.layout_alignSelf) {
                            "flex_end" -> lineHeight - placeable.height
                            "center" -> (lineHeight - placeable.height) / 2
                            "baseline" ->
                                placeable[FirstBaseline]
                                    .takeUnless {
                                        it == androidx.compose.ui.layout.AlignmentLine.Unspecified
                                    }
                                    ?.let { baseline - it } ?: 0
                            else -> 0
                        }
                    placeable.placeRelative(x, y + offset)
                    x += placeable.width
                }
                y += lineHeight
            }
        }
    }
}
