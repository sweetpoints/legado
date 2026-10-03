package io.legado.app.ui.widget

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.legado.app.R

internal data class PopupActionState(
    val items: List<PopupAction.PopupActionItem> = emptyList(),
    val vertical: Boolean = false,
    val dangerValues: Set<String> = emptySet(),
    val disabledValues: Set<String> = emptySet(),
    val maxWidth: Int = Int.MAX_VALUE,
    val maxHeight: Int = Int.MAX_VALUE,
)

internal data class PopupActionColors(val text: Color, val danger: Color, val disabled: Color)

internal fun popupItemEnabled(item: PopupAction.PopupActionItem, disabledValues: Set<String>) =
    item.enabled && item.value !in disabledValues

@Composable
internal fun PopupActionContent(
    state: PopupActionState,
    colors: PopupActionColors,
    dismiss: () -> Unit,
    action: (String) -> Unit,
) {
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val reserveIcon = state.vertical && state.items.any { it.icon != null }
    val reserveCheck = state.vertical && state.items.any { it.checkable || it.checked }
    val maximumWidth = with(density) { state.maxWidth.toDp() }
    val maximumHeight = with(density) { state.maxHeight.toDp() }
    val controls = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val direction =
            when (event.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                else -> null
            }
        when {
            event.key == Key.Escape -> {
                dismiss()
                true
            }
            direction != null -> {
                focus.moveFocus(direction)
                true
            }
            else -> false
        }
    }
    Box(
        Modifier.sizeIn(maxWidth = maximumWidth, maxHeight = maximumHeight)
            .then(controls)
            .padding(5.dp)
    ) {
        val scrolling = Modifier.verticalScroll(rememberScrollState())
        if (state.vertical) {
            Column(scrolling.widthIn(min = 112.dp, max = 280.dp).width(IntrinsicSize.Max)) {
                state.items.forEachIndexed { index, item ->
                    PopupActionRow(item, index, state, colors, reserveIcon, reserveCheck, action)
                }
            }
        } else {
            FlowRow(scrolling, horizontalArrangement = Arrangement.Start) {
                state.items.forEachIndexed { index, item ->
                    PopupActionRow(item, index, state, colors, false, false, action)
                }
            }
        }
    }
}

@Composable
private fun PopupActionRow(
    item: PopupAction.PopupActionItem,
    index: Int,
    state: PopupActionState,
    colors: PopupActionColors,
    reserveIcon: Boolean,
    reserveCheck: Boolean,
    action: (String) -> Unit,
) {
    val enabled = popupItemEnabled(item, state.disabledValues)
    val color =
        when {
            !enabled -> colors.disabled
            item.value in state.dangerValues -> colors.danger
            else -> colors.text
        }
    val activate = { if (popupItemEnabled(item, state.disabledValues)) action(item.value) }
    val interaction =
        if (item.checkable)
            Modifier.toggleable(
                item.checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { activate() },
            )
        else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = activate)
    Row(
        Modifier.heightIn(min = 48.dp)
            .then(interaction)
            .testTag("popup-action-$index")
            .padding(
                horizontal = if (state.vertical) 16.dp else 5.dp,
                vertical = if (state.vertical) 0.dp else 5.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.icon != null || reserveIcon) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                item.icon?.let { PopupActionIcon(it, color) }
            }
        }
        Text(
            item.title,
            modifier = if (state.vertical) Modifier.weight(1f) else Modifier,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.checked || reserveCheck) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterEnd) {
                if (item.checked)
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(24.dp),
                    )
            }
        }
    }
}

@Composable
private fun PopupActionIcon(drawable: Drawable, color: Color) {
    val pixels = with(LocalDensity.current) { 24.dp.roundToPx() }
    val bitmap =
        remember(drawable, pixels) {
            // A shared MenuItem Drawable must retain its original tint and bounds outside this
            // popup.
            val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable
            copy.toBitmap(pixels, pixels).asImageBitmap()
        }
    Image(bitmap, null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(color))
}
