package io.legado.app.ui.code.config

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable
fun CodeThemeScreen(state: CodeThemeUiState, onAuto: (Boolean) -> Unit, onSelect: (Int) -> Unit,
    onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = !state.loading && state.error == null
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.change_theme), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("code-theme-auto")
                .toggleable(value = state.automatic, enabled = enabled, role = Role.Switch, onValueChange = onAuto), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.system_auto), Modifier.weight(1f)); Switch(state.automatic, null, enabled = enabled)
            }
            if (state.loading) CircularProgressIndicator(Modifier.padding(16.dp))
            state.error?.let { Text(it); TextButton(onRetry) { Text(stringResource(R.string.retry)) } }
            val labels = listOf(R.string.edit_theme_monokai_dimmed, R.string.edit_theme_monokai,
                R.string.edit_theme_modern_dark, R.string.edit_theme_modern_light,
                R.string.edit_theme_solarized_dark, R.string.edit_theme_solarized_light,
                R.string.edit_theme_abyss, R.string.edit_theme_quiet_light)
            Column(Modifier.selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("code-theme-$index")
                        .selectable(selected = state.selected == index, enabled = enabled, role = Role.RadioButton) { onSelect(index) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(state.selected == index, null, enabled = enabled)
                        Text(stringResource(label), Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
