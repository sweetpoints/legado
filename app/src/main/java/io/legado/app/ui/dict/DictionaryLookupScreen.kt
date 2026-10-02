package io.legado.app.ui.dict

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryLookupScreen(state: DictionaryLookupUiState, onSelect: (String) -> Unit, onRetry: () -> Unit,
    modifier: Modifier = Modifier, result: @Composable (DictionaryResultDocument, Modifier) -> Unit) {
    val maximum = (LocalConfiguration.current.screenHeightDp * .75f).dp
    Surface(modifier.fillMaxWidth().heightIn(max = maximum), color = MaterialTheme.colorScheme.surface) {
        Column {
            if (state.rules.isNotEmpty()) {
                val index = state.rules.indexOfFirst { it.name == state.selected }.coerceAtLeast(0)
                val tabs: @Composable () -> Unit = {
                    state.rules.forEachIndexed { position, rule -> Tab(position == index, { onSelect(rule.name) },
                        text = { Text(rule.name) }, modifier = Modifier.testTag("dictionary-tab-${rule.name}")) }
                }
                if (state.rules.size <= 4) PrimaryTabRow(index) { tabs() }
                else PrimaryScrollableTabRow(index, edgePadding = 0.dp) { tabs() }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("dictionary-lookup-loading"))
            state.error?.let { error -> Column(Modifier.padding(16.dp)) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("dictionary-lookup-error"))
                TextButton(onRetry, modifier = Modifier.testTag("dictionary-lookup-retry")) { Text(stringResource(R.string.retry)) }
            } }
            if (!state.loading && state.error == null && state.rules.isEmpty()) Text(stringResource(R.string.dictionary_no_enabled_rule), Modifier.padding(16.dp).testTag("dictionary-lookup-empty"))
            state.document?.let { result(it, Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 120.dp, max = maximum - 56.dp).testTag("dictionary-result")) }
        }
    }
}
