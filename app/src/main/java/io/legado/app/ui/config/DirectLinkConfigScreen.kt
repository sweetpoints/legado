package io.legado.app.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.DirectLinkDraft
import io.legado.app.data.repository.DirectLinkIssue

class DirectLinkConfigActions(val edit: ((DirectLinkDraft) -> DirectLinkDraft) -> Unit = {}, val preset: (Int) -> Unit = {},
    val copy: () -> Unit = {}, val paste: () -> Unit = {}, val test: () -> Unit = {}, val cancelTest: () -> Unit = {},
    val save: () -> Unit = {}, val close: () -> Unit = {}, val retry: () -> Unit = {}, val clearResult: () -> Unit = {},
    val copyResult: () -> Unit = {})
@Composable fun DirectLinkConfigScreen(state: DirectLinkConfigState, actions: DirectLinkConfigActions) {
    var menu by rememberSaveable { mutableStateOf(false) }; var presets by rememberSaveable { mutableStateOf(false) }
    val draft = state.session?.draft ?: DirectLinkDraft()
    val editable = !state.busy && !state.finished && state.session != null
    Surface {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f).imePadding()) {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.direct_link_upload_config), Modifier.weight(1f).padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    Box {
                        IconButton({ menu = true }, Modifier.testTag("direct-link-menu"), enabled = editable) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.copy_rule)) }, { menu = false; actions.copy() }, Modifier.testTag("direct-link-copy"))
                            DropdownMenuItem({ Text(stringResource(R.string.paste_rule)) }, { menu = false; actions.paste() }, Modifier.testTag("direct-link-paste"))
                            DropdownMenuItem({ Text(stringResource(R.string.import_default_rule)) }, { menu = false; presets = true }, Modifier.testTag("direct-link-defaults"))
                        }
                    }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                OutlinedTextField(draft.uploadUrl, { text -> actions.edit { it.copy(uploadUrl = text) } }, Modifier.fillMaxWidth().testTag("direct-link-upload"), enabled = editable,
                    label = { Text(stringResource(R.string.upload_url)) }, isError = state.issue == DirectLinkIssue.Upload)
                OutlinedTextField(draft.downloadRule, { text -> actions.edit { it.copy(downloadRule = text) } }, Modifier.fillMaxWidth().testTag("direct-link-download"), enabled = editable,
                    label = { Text(stringResource(R.string.download_url_rule)) }, isError = state.issue == DirectLinkIssue.Download)
                OutlinedTextField(draft.summary, { text -> actions.edit { it.copy(summary = text) } }, Modifier.fillMaxWidth().testTag("direct-link-summary"), enabled = editable,
                    label = { Text(stringResource(R.string.summary)) }, isError = state.issue == DirectLinkIssue.Summary)
                OutlinedTextField(draft.expiry, { text -> actions.edit { it.copy(expiry = text) } }, Modifier.fillMaxWidth().testTag("direct-link-expiry"), enabled = editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.share_expiry_days)) }, isError = state.issue == DirectLinkIssue.Expiry)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = draft.compress, enabled = editable, role = Role.Checkbox,
                    onValueChange = { value -> actions.edit { it.copy(compress = value) } }).testTag("direct-link-compress"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(draft.compress, null); Text(stringResource(R.string.is_compress))
                }
                state.issue?.let { issue -> Text(stringResource(when (issue) {
                    DirectLinkIssue.Upload -> R.string.direct_link_upload_required
                    DirectLinkIssue.Download -> R.string.direct_link_download_required
                    DirectLinkIssue.Summary -> R.string.direct_link_summary_required
                    DirectLinkIssue.Expiry -> R.string.invalid_share_expiry_days
                    DirectLinkIssue.Clipboard -> R.string.direct_link_clipboard_invalid
                }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("direct-link-validation")) }
                state.error?.let { message -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(actions.retry, enabled = !state.busy, modifier = Modifier.testTag("direct-link-retry")) { Text(stringResource(R.string.retry)) }
                } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(if (state.testing) actions.cancelTest else actions.test, Modifier.testTag("direct-link-test"), enabled = editable || state.testing) {
                    Text(stringResource(if (state.testing) R.string.stop else R.string.test))
                }
                Spacer(Modifier.weight(1f))
                TextButton(actions.close, Modifier.testTag("direct-link-cancel"), enabled = !state.saving) { Text(stringResource(R.string.cancel)) }
                TextButton(actions.save, Modifier.testTag("direct-link-save"), enabled = editable) { Text(stringResource(R.string.ok)) }
            }
        }
    }
    if (presets) AlertDialog(onDismissRequest = { presets = false }, title = { Text(stringResource(R.string.import_default_rule)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            state.defaults.forEachIndexed { index, value -> TextButton({ presets = false; actions.preset(index) }, Modifier.fillMaxWidth().testTag("direct-link-preset-$index")) {
                Text(value.summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } }
        } }, confirmButton = { TextButton({ presets = false }) { Text(stringResource(R.string.cancel)) } })
    state.session?.result?.let { result -> AlertDialog(onDismissRequest = actions.clearResult, title = { Text(stringResource(R.string.direct_link_test_result)) },
        text = { SelectionContainer { Text(result, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("direct-link-result")) } },
        confirmButton = { TextButton(actions.clearResult, Modifier.testTag("direct-link-result-close")) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(actions.copyResult, Modifier.testTag("direct-link-result-copy")) { Text(stringResource(R.string.copy_text)) } }) }
}
