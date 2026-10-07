package io.legado.app.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable
fun SourceCheckSettingsScreen(
    state: SourceCheckSettingsState,
    seconds: (String) -> Unit,
    toggle: (SourceCheckOption) -> Unit,
    save: () -> Unit,
    cancel: () -> Unit,
    retry: () -> Unit,
) {
    Surface {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(
                    stringResource(R.string.check_source_config),
                    Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(
                Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.settings != null) {
                    val problem =
                        when (state.timeoutIssue) {
                            SourceCheckTimeoutIssue.Empty ->
                                stringResource(R.string.timeout) +
                                    stringResource(R.string.cannot_empty)
                            SourceCheckTimeoutIssue.Invalid ->
                                stringResource(R.string.source_check_timeout_invalid)
                            null -> null
                        }
                    OutlinedTextField(
                        state.seconds,
                        seconds,
                        Modifier.fillMaxWidth().testTag("source-check-seconds"),
                        enabled = !state.saving,
                        label = { Text(stringResource(R.string.check_source_timeout)) },
                        isError = problem != null,
                        supportingText = { if (problem != null) Text(problem) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    CheckOption(
                        SourceCheckOption.Comment,
                        R.string.source_check_detail,
                        state.settings.comment,
                        !state.saving,
                        toggle,
                    )
                    Text(
                        stringResource(R.string.check_source_item),
                        Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    FlowRow(Modifier.fillMaxWidth()) {
                        CheckOption(
                            SourceCheckOption.Domain,
                            R.string.domain,
                            state.settings.domain,
                            !state.saving,
                            toggle,
                        )
                        CheckOption(
                            SourceCheckOption.Search,
                            R.string.search,
                            state.settings.search,
                            !state.saving,
                            toggle,
                        )
                        CheckOption(
                            SourceCheckOption.Discovery,
                            R.string.discovery,
                            state.settings.discovery,
                            !state.saving,
                            toggle,
                        )
                        CheckOption(
                            SourceCheckOption.Info,
                            R.string.source_tab_info,
                            state.settings.info,
                            !state.saving && state.infoEnabled,
                            toggle,
                        )
                        CheckOption(
                            SourceCheckOption.Category,
                            R.string.chapter_list,
                            state.settings.category,
                            !state.saving && state.categoryEnabled,
                            toggle,
                        )
                        CheckOption(
                            SourceCheckOption.Content,
                            R.string.main_body,
                            state.settings.content,
                            !state.saving && state.contentEnabled,
                            toggle,
                        )
                    }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    if (state.settings == null)
                        TextButton(retry, enabled = !state.loading) {
                            Text(stringResource(R.string.retry))
                        }
                }
            }
            Row(Modifier.align(Alignment.End).padding(horizontal = 8.dp)) {
                TextButton(
                    cancel,
                    Modifier.testTag("source-check-cancel"),
                    enabled = !state.saving,
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    save,
                    Modifier.testTag("source-check-save"),
                    enabled = !state.saving && state.settings != null,
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
}

@Composable
private fun CheckOption(
    option: SourceCheckOption,
    label: Int,
    checked: Boolean,
    enabled: Boolean,
    toggle: (SourceCheckOption) -> Unit,
) {
    Row(
        Modifier.heightIn(min = 48.dp)
            .testTag("source-check-" + option.name)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { toggle(option) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(stringResource(label), Modifier.padding(end = 12.dp))
    }
}
