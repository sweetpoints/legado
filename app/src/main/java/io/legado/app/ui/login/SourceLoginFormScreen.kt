package io.legado.app.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.data.repository.SourceLoginRow

@Composable
internal fun SourceLoginFormScreen(
    state: SourceLoginFormUiState,
    onEdit: (String, String) -> Unit,
    onChoose: (SourceLoginRow, String) -> Unit,
    onAction: (SourceLoginRow, Boolean) -> Unit,
    onToggle: (SourceLoginRow, Boolean) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    onHeader: () -> Unit,
    onDeleteHeader: () -> Unit,
    onCopyHeader: () -> Unit,
    onCloseHeader: () -> Unit,
    onClearRequest: (Boolean) -> Unit,
    onClear: () -> Unit,
    onLog: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClose, Modifier.testTag("source-login-close")) {
                    Text(stringResource(R.string.cancel))
                }
                Text(
                    stringResource(R.string.login_source, state.title),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!state.v2)
                    TextButton(
                        onSubmit,
                        enabled =
                            state.rendered && !state.rendering && !state.loading && !state.busy,
                        modifier = Modifier.testTag("source-login-submit"),
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                Box {
                    TextButton({ menu = true }, Modifier.testTag("source-login-menu")) { Text("⋮") }
                    DropdownMenu(menu, { menu = false }) {
                        listOf(
                                Triple(
                                    R.string.show_login_header,
                                    R.drawable.ic_add_online,
                                    onHeader,
                                ),
                                Triple(
                                    R.string.del_login_header,
                                    R.drawable.ic_clear,
                                    onDeleteHeader,
                                ),
                                Triple(
                                    R.string.clear_login_info,
                                    R.drawable.ic_clear_all,
                                    { onClearRequest(true) },
                                ),
                                Triple(R.string.log, R.drawable.ic_history, onLog),
                            )
                            .forEach { (label, icon, action) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    leadingIcon = {
                                        Icon(
                                            painterResource(icon),
                                            null,
                                            Modifier.testTag("source-login-menu-icon:$label"),
                                        )
                                    },
                                    onClick = {
                                        menu = false
                                        action()
                                    },
                                )
                            }
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                if (state.loading)
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("source-login-loading"))
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp),
                    )
                    TextButton(onRetry, Modifier.testTag("source-login-retry")) {
                        Text(stringResource(R.string.retry))
                    }
                }
                SourceLoginFormLayout(state.rows, Modifier.fillMaxWidth()) { row ->
                    SourceLoginFormControl(row, state, onEdit, onChoose, onAction, onToggle)
                }
            }
        }
    }
    state.header?.let { header ->
        AlertDialog(
            onDismissRequest = onCloseHeader,
            title = { Text(stringResource(R.string.login_header)) },
            text = { Text(if (header.isBlank()) stringResource(R.string.empty) else header) },
            confirmButton = { TextButton(onCloseHeader) { Text(stringResource(R.string.ok)) } },
            dismissButton = {
                if (header.isNotBlank())
                    TextButton(onCopyHeader) { Text(stringResource(R.string.copy_text)) }
            },
        )
    }
    if (state.clear)
        AlertDialog(
            onDismissRequest = { onClearRequest(false) },
            title = { Text(stringResource(R.string.clear_login_info)) },
            text = { Text(stringResource(R.string.sure_del)) },
            confirmButton = { TextButton(onClear) { Text(stringResource(R.string.yes)) } },
            dismissButton = {
                TextButton({ onClearRequest(false) }) { Text(stringResource(R.string.no)) }
            },
        )
}

@Composable
private fun SourceLoginFormControl(
    row: SourceLoginRow,
    state: SourceLoginFormUiState,
    onEdit: (String, String) -> Unit,
    onChoose: (SourceLoginRow, String) -> Unit,
    onAction: (SourceLoginRow, Boolean) -> Unit,
    onToggle: (SourceLoginRow, Boolean) -> Unit,
) {
    val text = state.values[row.key].orEmpty()
    val enabled =
        !state.busy &&
            !state.loading &&
            !state.finished &&
            (row.action?.let { state.countdowns.getOrDefault(it, 0) } ?: 0) == 0
    val align =
        when (row.style.layout_justifySelf) {
            "center" -> TextAlign.Center
            "flex_end" -> TextAlign.End
            "flex_start" -> TextAlign.Start
            else -> TextAlign.Start
        }
    val tag = "source-login-field:${row.key}"
    when (row.type) {
        RowUi.Type.text,
        RowUi.Type.password -> {
            var visible by rememberSaveable(row.key) { mutableStateOf(false) }
            var value by
                rememberSaveable(row.key, stateSaver = TextFieldValue.Saver) {
                    mutableStateOf(TextFieldValue(text))
                }
            LaunchedEffect(text) {
                if (value.text != text)
                    value =
                        TextFieldValue(
                            text,
                            TextRange(
                                value.selection.start.coerceAtMost(text.length),
                                value.selection.end.coerceAtMost(text.length),
                            ),
                        )
            }
            OutlinedTextField(
                value,
                {
                    value = it
                    onEdit(row.key, it.text)
                },
                label = { Text(row.label) },
                placeholder = { row.hint?.let { Text(it) } },
                isError = row.key in state.errors,
                supportingText = { state.errors[row.key]?.let { Text(it) } },
                enabled = !state.finished,
                textStyle = LocalTextStyle.current.copy(textAlign = align),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            if (row.type == RowUi.Type.password) KeyboardType.Password
                            else KeyboardType.Text
                    ),
                visualTransformation =
                    if (row.type == RowUi.Type.password && !visible) PasswordVisualTransformation()
                    else VisualTransformation.None,
                trailingIcon = {
                    if (row.type == RowUi.Type.password)
                        TextButton(
                            { visible = !visible },
                            Modifier.testTag("source-login-password:${row.key}"),
                        ) {
                            Icon(
                                painterResource(
                                    if (visible)
                                        com.google.android.material.R.drawable
                                            .design_ic_visibility_off
                                    else com.google.android.material.R.drawable.design_ic_visibility
                                ),
                                stringResource(
                                    if (visible) R.string.source_login_hide_password
                                    else R.string.source_login_show_password
                                ),
                            )
                        }
                },
                modifier = Modifier.fillMaxWidth().padding(4.dp).heightIn(min = 48.dp).testTag(tag),
            )
        }
        RowUi.Type.select -> {
            var expanded by remember { mutableStateOf(false) }
            Box(Modifier.padding(4.dp).testTag(tag)) {
                OutlinedButton({ expanded = true }, enabled = !state.finished) {
                    Text("${row.label}: $text", textAlign = align)
                }
                DropdownMenu(expanded, { expanded = false }) {
                    row.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                expanded = false
                                onChoose(row, option)
                            },
                        )
                    }
                }
                state.errors[row.key]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        RowUi.Type.toggle ->
            if (state.v2)
                Row(
                    Modifier.padding(4.dp).heightIn(min = 48.dp).testTag(tag),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.label, Modifier.weight(1f))
                    Switch(text == "true", { onToggle(row, false) }, enabled = enabled)
                }
            else
                SourceLoginActionBox(row, enabled, { long -> onToggle(row, long) }, tag) {
                    Text(
                        if (row.style.layout_justifySelf == "right") row.label + text
                        else text + row.label,
                        textAlign = align,
                    )
                }
        RowUi.Type.button ->
            SourceLoginActionBox(
                row,
                enabled,
                { long -> onAction(row, long) },
                "source-login-action:${row.action ?: row.name}",
            ) {
                val countdown = (row.action?.let { state.countdowns.getOrDefault(it, 0) } ?: 0)
                Text(row.label + if (countdown > 0) " (${countdown}s)" else "", textAlign = align)
            }
        RowUi.Type.label ->
            Text(
                row.label,
                Modifier.padding(12.dp).testTag("source-login-label:${row.name}"),
                textAlign = align,
            )
    }
}

@Composable
private fun SourceLoginActionBox(
    row: SourceLoginRow,
    enabled: Boolean,
    onAction: (Boolean) -> Unit,
    tag: String,
    content: @Composable () -> Unit,
) {
    val action by rememberUpdatedState(onAction)
    val active by rememberUpdatedState(enabled)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier =
            Modifier.padding(4.dp)
                .heightIn(min = 48.dp)
                .testTag(tag)
                .semantics {
                    role = Role.Button
                    if (!enabled) disabled()
                    onClick {
                        if (enabled) {
                            action(false)
                            true
                        } else false
                    }
                    onLongClick {
                        if (enabled) {
                            action(true)
                            true
                        } else false
                    }
                }
                .pointerInput(row.key, row.action) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                            if (active) action(up.uptimeMillis > down.uptimeMillis + 666)
                        }
                    }
                },
    ) {
        Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) { content() }
    }
}
