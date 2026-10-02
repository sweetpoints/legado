package io.legado.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.components.LegadoTopAppBar

@Composable
fun CoverRuleScreen(
    state: CoverRuleUiState,
    onEnabledChange: (Boolean) -> Unit,
    onSearchUrlChange: (String) -> Unit,
    onCoverRuleChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        LegadoTopAppBar(stringResource(R.string.cover_config), onCancel,
            windowInsets = WindowInsets(0, 0, 0, 0))
        if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("cover-rule-progress"))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().testTag("cover-rule-enabled")
                .toggleable(state.draft.enabled, enabled = !state.isBusy, role = Role.Checkbox,
                    onValueChange = onEnabledChange)) {
                Checkbox(state.draft.enabled, onCheckedChange = null, enabled = !state.isBusy)
                Text(stringResource(R.string.enable), Modifier.padding(top = 12.dp))
            }
            OutlinedTextField(state.draft.searchUrl, onSearchUrlChange,
                modifier = Modifier.fillMaxWidth().testTag("cover-rule-search-url"),
                enabled = !state.isBusy, label = { Text(stringResource(R.string.r_search_url)) },
                isError = state.showValidation && state.draft.searchUrl.isBlank())
            OutlinedTextField(state.draft.coverRule, onCoverRuleChange,
                modifier = Modifier.fillMaxWidth().testTag("cover-rule-expression"),
                enabled = !state.isBusy, label = { Text(stringResource(R.string.rule_cover_url)) },
                isError = state.showValidation && state.draft.coverRule.isBlank())
            if (state.showValidation && (state.draft.searchUrl.isBlank() || state.draft.coverRule.isBlank())) {
                Text(stringResource(R.string.cover_rule_required_fields), color = MaterialTheme.colorScheme.error)
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onRetry, enabled = !state.isBusy) { Text(stringResource(R.string.retry)) }
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End) {
            TextButton(onDelete, enabled = !state.isBusy, modifier = Modifier.testTag("cover-rule-delete")) {
                Text(stringResource(R.string.btn_default_s))
            }
            TextButton(onCancel, enabled = !state.isSaving, modifier = Modifier.testTag("cover-rule-cancel")) {
                Text(stringResource(R.string.cancel))
            }
            TextButton(onSave, enabled = !state.isBusy, modifier = Modifier.testTag("cover-rule-save")) {
                Text(stringResource(R.string.ok))
            }
        }
    }
}
