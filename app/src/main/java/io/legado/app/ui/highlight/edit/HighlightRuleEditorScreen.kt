package io.legado.app.ui.highlight.edit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.repository.HighlightRuleDraft
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.book.read.HighlightFillPreviewDrawable

@Composable
internal fun HighlightRuleEditorScreen(
    state: HighlightRuleEditorState,
    onChange: ((HighlightRuleDraft) -> HighlightRuleDraft) -> Unit,
    onStyle: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rule = state.draft?.rule
    val enabled = rule != null && !state.loading && !state.saving && !state.finished
    Surface(modifier.padding(16.dp), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(
                    stringResource(R.string.highlight_rule_edit_title),
                    Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(
                        onRetry,
                        Modifier.testTag("highlight-rule-retry"),
                        enabled = !state.loading && !state.saving,
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                }
                RuleTextField(
                    rule?.name.orEmpty(),
                    { text -> onChange { it.copy(name = text) } },
                    R.string.name,
                    "name",
                    enabled,
                )
                RuleTextField(
                    rule?.group.orEmpty(),
                    { text -> onChange { it.copy(group = text) } },
                    R.string.highlight_rule_group_hint,
                    "group",
                    enabled,
                )
                RuleTextField(
                    rule?.pattern.orEmpty(),
                    { text -> onChange { it.copy(pattern = text) } },
                    R.string.highlight_rule_pattern,
                    "pattern",
                    enabled,
                    8,
                )
                RuleCheck(
                    rule?.isRegex == true,
                    { checked -> onChange { it.copy(isRegex = checked) } },
                    R.string.use_regex,
                    "regex",
                    enabled,
                )
                RuleCheck(
                    rule?.applyToBody == true,
                    { checked -> onChange { it.copy(applyToBody = checked) } },
                    R.string.scope_content,
                    "body",
                    enabled,
                )
                RuleCheck(
                    rule?.applyToTitle == true,
                    { checked -> onChange { it.copy(applyToTitle = checked) } },
                    R.string.highlight_rule_apply_to_title,
                    "title",
                    enabled,
                )
                RuleTextField(
                    rule?.scope.orEmpty(),
                    { text -> onChange { it.copy(scope = text) } },
                    R.string.highlight_rule_scope_hint,
                    "scope",
                    enabled,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onStyle,
                        Modifier.testTag("highlight-rule-style"),
                        enabled = enabled,
                    ) {
                        Text(stringResource(R.string.highlight_style))
                    }
                    HighlightRuleStylePreview(
                        rule?.style ?: HighlightStyle(),
                        Modifier.padding(start = 12.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onCancel,
                    Modifier.testTag("highlight-rule-cancel"),
                    enabled = !state.saving && !state.finished,
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onSave, Modifier.testTag("highlight-rule-save"), enabled = enabled) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
}

@Composable
private fun RuleTextField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    field: String,
    enabled: Boolean,
    maxLines: Int = 3,
) {
    var start by rememberSaveable { mutableIntStateOf(0) }
    var end by rememberSaveable { mutableIntStateOf(0) }
    var local by remember {
        mutableStateOf(
            TextFieldValue(
                value,
                TextRange(start.coerceIn(0, value.length), end.coerceIn(0, value.length)),
            )
        )
    }
    LaunchedEffect(value) {
        if (local.text != value)
            local =
                TextFieldValue(
                    value,
                    TextRange(start.coerceIn(0, value.length), end.coerceIn(0, value.length)),
                )
    }
    OutlinedTextField(
        local,
        { next ->
            local = next
            start = next.selection.start
            end = next.selection.end
            onChange(next.text)
        },
        Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("highlight-rule-$field"),
        enabled = enabled,
        label = { Text(stringResource(label)) },
        minLines = 1,
        maxLines = maxLines,
    )
}

@Composable
private fun RuleCheck(
    value: Boolean,
    onChange: (Boolean) -> Unit,
    label: Int,
    tag: String,
    enabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("highlight-rule-$tag")
            .toggleable(
                value = value,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(value, null)
        Text(stringResource(label), Modifier.weight(1f))
    }
}

/** Shared reader fill renderer is drawn directly into Compose's canvas, without a View bridge. */
@Composable
internal fun HighlightRuleStylePreview(style: HighlightStyle, modifier: Modifier = Modifier) {
    val pixels = with(LocalDensity.current) { 16.sp.toPx() }
    val drawable = remember(style, pixels) { HighlightFillPreviewDrawable(style, pixels) }
    Text(
        stringResource(R.string.highlight_style),
        modifier
            .testTag("highlight-rule-preview")
            .drawBehind {
                if (style.fill != 0)
                    drawIntoCanvas { canvas ->
                        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        drawable.draw(canvas.nativeCanvas)
                    }
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color =
            if (style.textColor == 0) MaterialTheme.colorScheme.onSurface
            else Color(style.textColor),
        fontSize = 16.sp,
    )
}
