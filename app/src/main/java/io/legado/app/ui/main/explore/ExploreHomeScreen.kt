package io.legado.app.ui.main.explore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ExploreHomeActions(
    val query: (String) -> Unit,
    val expand: (String) -> Unit,
    val action: (String, String) -> Unit,
    val control: (Int) -> Unit,
    val value: (Int, String) -> Unit,
    val delete: () -> Unit,
    val dismiss: () -> Unit,
    val retry: () -> Unit,
)

@Composable
internal fun ExploreHomeScreen(state: ExploreHomeState, actions: ExploreHomeActions) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.scrollRequest) {
        if (state.scrollRequest > 0) {
            val visibleOffset =
                listState.layoutInfo.visibleItemsInfo
                    .find { it.index == state.scrollTarget }
                    ?.offset
            if (state.expandedUrl == null && !state.eInkMode)
                listState.animateScrollToItem(state.scrollTarget)
            else listState.scrollToItem(state.scrollTarget, -(visibleOffset ?: 0))
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        ExploreHomeToolbar(state, actions)
        state.error?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f).padding(8.dp))
                TextButton(actions.retry) { Text(stringResource(R.string.retry)) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("explore-home-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(state.sources, key = { _, source -> source.url }) { _, source ->
                    ExploreHomeCard(source, state, actions)
                }
            }
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            if (!state.loading && state.sources.isEmpty() && state.query.isEmpty()) {
                Text(
                    stringResource(R.string.explore_empty),
                    Modifier.align(Alignment.Center).padding(16.dp),
                    textAlign = TextAlign.Center,
                )
            }
            ExploreHomeScroller(
                listState,
                state.sources.size,
                state.showFastScroller,
                Modifier.align(Alignment.CenterEnd),
            )
        }
    }
    state.deleteUrl?.let { url ->
        AlertDialog(
            onDismissRequest = actions.dismiss,
            title = { Text(stringResource(R.string.draw)) },
            text = {
                Text(
                    stringResource(R.string.sure_del) +
                        "\n" +
                        (state.sources.find { it.url == url }?.name ?: url)
                )
            },
            confirmButton = {
                TextButton(actions.delete, enabled = !state.busy) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(actions.dismiss, enabled = !state.busy) {
                    Text(stringResource(R.string.no))
                }
            },
        )
    }
    state.errorText?.let { detail ->
        AlertDialog(
            onDismissRequest = actions.dismiss,
            title = { Text("ERROR") },
            text = { Text(detail) },
            confirmButton = {
                TextButton(actions.dismiss) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
}

@Composable
private fun ExploreHomeToolbar(state: ExploreHomeState, actions: ExploreHomeActions) {
    var groupMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            state.query,
            actions.query,
            Modifier.weight(1f).testTag("explore-home-query"),
            label = { Text(stringResource(R.string.screen_find)) },
            singleLine = true,
            enabled = !state.busy,
        )
        Box {
            TextButton(
                { groupMenu = true },
                enabled = !state.busy,
                modifier = Modifier.testTag("explore-home-groups"),
            ) {
                Text(stringResource(R.string.group))
            }
            DropdownMenu(groupMenu, { groupMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.all_source)) },
                    onClick = {
                        actions.query("")
                        groupMenu = false
                    },
                    leadingIcon = { Checkbox(isExploreAllQuery(state.query), null) },
                )
                state.groups.forEach { group ->
                    DropdownMenuItem(
                        text = { Text(group) },
                        onClick = {
                            actions.query("group:$group")
                            groupMenu = false
                        },
                        leadingIcon = {
                            Checkbox(
                                selectedExploreGroup(state.query, state.groups.toSet()) == group,
                                null,
                            )
                        },
                    )
                }
            }
        }
        IconButton({ actions.action("manage", "") }, enabled = !state.busy) {
            Icon(Icons.Default.Settings, stringResource(R.string.book_source_manage))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreHomeCard(
    source: ExploreHomeSource,
    state: ExploreHomeState,
    actions: ExploreHomeActions,
) {
    var menu by remember(source.url) { mutableStateOf(false) }
    val expanded = source.url == state.expandedUrl
    Card(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("explore-home-source:${source.url}")
    ) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    enabled = !state.busy,
                    onClick = { actions.expand(source.url) },
                    onLongClick = { menu = true },
                )
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(source.name, Modifier.weight(1f))
            if (expanded && state.panelLoading)
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 1.dp)
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
            )
            Box {
                IconButton({ menu = true }, enabled = !state.busy) {
                    Icon(Icons.Default.MoreVert, "${stringResource(R.string.menu)} ${source.name}")
                }
                DropdownMenu(menu, { menu = false }) {
                    listOf(
                            "edit" to R.string.edit,
                            "top" to R.string.to_top,
                            "login" to R.string.login,
                            "search" to R.string.search,
                            "refresh" to R.string.refresh,
                            "delete" to R.string.delete,
                        )
                        .filter { it.first in visibleExploreHomeRowActions(source.hasLogin) }
                        .forEach { (action, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(label),
                                        color =
                                            if (action == "delete") MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = {
                                    menu = false
                                    actions.action(action, source.url)
                                },
                            )
                        }
                }
            }
        }
        if (expanded) {
            ExploreControlLayout(state.controls, Modifier.fillMaxWidth().padding(8.dp)) { control ->
                ExploreHomeControl(control, state.busy, actions)
            }
        }
    }
}

@Composable
private fun ExploreHomeControl(
    control: ExploreHomeControl,
    busy: Boolean,
    actions: ExploreHomeActions,
) {
    val modifier = Modifier.testTag("explore-home-control:${control.id}")
    val alignment =
        when (control.style.justify) {
            "flex_start" -> TextAlign.Start
            "flex_end" -> TextAlign.End
            else -> TextAlign.Center
        }
    when (control.type) {
        "text" -> {
            var submittedValue by
                remember(control.id, control.title) { mutableStateOf(control.value) }
            LaunchedEffect(control.value) {
                if (control.value != submittedValue) {
                    delay(600)
                    actions.control(control.id)
                    submittedValue = control.value
                }
            }
            OutlinedTextField(
                control.value,
                { actions.value(control.id, it) },
                modifier,
                label = { Text(control.label) },
                enabled = !busy,
            )
        }
        "select" -> {
            var menu by remember { mutableStateOf(false) }
            Box(modifier) {
                TextButton({ menu = true }, enabled = !busy) {
                    Text("${control.label}: ${control.value}", textAlign = alignment)
                }
                DropdownMenu(menu, { menu = false }) {
                    control.choices.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice) },
                            onClick = {
                                menu = false
                                actions.value(control.id, choice)
                                actions.control(control.id)
                            },
                        )
                    }
                }
            }
        }
        "toggle" ->
            TextButton({ actions.control(control.id) }, modifier, enabled = !busy) {
                Text(
                    if (control.style.justify == "right") control.label + control.value
                    else control.value + control.label,
                    textAlign = alignment,
                )
            }
        "button",
        "url" ->
            TextButton({ actions.control(control.id) }, modifier, enabled = !busy) {
                Text(control.label, textAlign = alignment)
            }
    }
}

/** Measure each control once after computing its row's flex allocation from intrinsic sizes. */
@Composable
private fun ExploreControlLayout(
    controls: List<ExploreHomeControl>,
    modifier: Modifier,
    content: @Composable (ExploreHomeControl) -> Unit,
) {
    val visible = controls.filter { it.type in setOf("url", "button", "text", "toggle", "select") }
    Layout(
        content = {
            visible.forEach { control -> key(control.title, control.id) { content(control) } }
        },
        modifier = modifier,
    ) { children, constraints ->
        val availableWidth = constraints.maxWidth
        val spacing = 4.dp.roundToPx()
        val preferred = children.mapIndexed { index, child ->
            val basis = visible[index].style.basis
            if (basis >= 0)
                (availableWidth * basis).roundToInt().coerceAtLeast(0).let { requested ->
                    if (visible[index].style.shrink > 0) requested.coerceAtMost(availableWidth)
                    else requested
                }
            else
                runCatching { child.maxIntrinsicWidth(Constraints.Infinity) }
                    .getOrDefault(availableWidth)
                    .let { naturalWidth ->
                        if (visible[index].style.shrink > 0)
                            naturalWidth.coerceAtMost(availableWidth)
                        else naturalWidth
                    }
        }
        val rows = exploreControlRows(preferred, visible.map { it.style }, availableWidth, spacing)
        var y = 0
        val placements = mutableListOf<Triple<Placeable, Int, Int>>()
        for (row in rows) {
            val free =
                (availableWidth - row.sumOf { preferred[it] } - spacing * (row.size - 1))
                    .coerceAtLeast(0)
            val growth = row.sumOf { visible[it].style.grow.toDouble() }.toFloat()
            val widths = row.associateWith { index ->
                preferred[index] +
                    if (growth > 0) (free * visible[index].style.grow / growth).roundToInt() else 0
            }
            val rowHeight =
                row.maxOfOrNull {
                    runCatching { children[it].maxIntrinsicHeight(widths.getValue(it)) }
                        .getOrDefault(0)
                } ?: 0
            var x = 0
            for (index in row) {
                val style = visible[index].style
                val width = widths.getValue(index)
                val child =
                    children[index].measure(
                        Constraints(
                            minWidth = width,
                            maxWidth = width,
                            minHeight =
                                if (style.align == "stretch" || style.align == "auto") rowHeight
                                else 0,
                            maxHeight = Constraints.Infinity,
                        )
                    )
                val offset =
                    when (style.align) {
                        "flex_end" -> rowHeight - child.height
                        "center" -> (rowHeight - child.height) / 2
                        else -> 0
                    }
                placements += Triple(child, x, y + offset)
                x += width + spacing
            }
            y += rowHeight + spacing
        }
        layout(availableWidth, constraints.constrainHeight(max(0, y - spacing))) {
            placements.forEach { (child, x, top) -> child.placeRelative(x, top) }
        }
    }
}

internal fun exploreControlRows(
    widths: List<Int>,
    styles: List<ExploreControlStyle>,
    availableWidth: Int,
    spacing: Int,
): List<List<Int>> {
    val rows = mutableListOf<List<Int>>()
    var row = mutableListOf<Int>()
    var width = 0
    widths.forEachIndexed { index, childWidth ->
        val next = width + (if (row.isEmpty()) 0 else spacing) + childWidth
        if (row.isNotEmpty() && (styles[index].wrapBefore || next > availableWidth)) {
            rows += row.toList()
            row = mutableListOf()
            width = 0
        }
        width += (if (row.isEmpty()) 0 else spacing) + childWidth
        row += index
    }
    if (row.isNotEmpty()) rows += row.toList()
    return rows
}

@Composable
private fun ExploreHomeScroller(
    list: LazyListState,
    count: Int,
    fast: Boolean,
    modifier: Modifier,
) {
    if (count < 2 || (!fast && !list.isScrollInProgress)) return
    val scope = rememberCoroutineScope()
    val color = MaterialTheme.colorScheme.primary
    val input =
        if (fast)
            Modifier.pointerInput(count) {
                    fun scroll(y: Float) {
                        scope.launch {
                            list.scrollToItem(
                                ((y / size.height) * count).toInt().coerceIn(0, count - 1)
                            )
                        }
                    }
                    detectTapGestures { scroll(it.y) }
                }
                .pointerInput(count) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        scope.launch {
                            list.scrollToItem(
                                ((change.position.y / size.height) * count)
                                    .toInt()
                                    .coerceIn(0, count - 1)
                            )
                        }
                    }
                }
        else Modifier
    Canvas(
        modifier
            .width(if (fast) 32.dp else 4.dp)
            .fillMaxSize()
            .then(input)
            .testTag("explore-home-scroller")
            .semantics { contentDescription = "${list.firstVisibleItemIndex + 1}/$count" }
    ) {
        val height = (size.height / count).coerceAtLeast(24.dp.toPx())
        val top = (size.height - height) * list.firstVisibleItemIndex / (count - 1)
        drawRoundRect(
            color,
            Offset(size.width - 4.dp.toPx(), top),
            Size(4.dp.toPx(), height),
            CornerRadius(2.dp.toPx()),
        )
    }
}
