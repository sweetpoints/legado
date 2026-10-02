package io.legado.app.ui.book.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable internal fun SearchScopeScreen(state: SearchScopeState, onTab: (SearchScopeTab) -> Unit,
    onGroup: (String) -> Unit, onSource: (String) -> Unit, onQuery: (String) -> Unit,
    onExpand: () -> Unit, onRetry: () -> Unit, onAll: () -> Unit, onCancel: () -> Unit,
    onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.search_scope), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (state.tab == SearchScopeTab.Sources) IconButton(onExpand,
                        Modifier.testTag("search-scope-filter"), enabled = !state.finished) {
                        Icon(painterResource(R.drawable.ic_screen), stringResource(R.string.screen))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                SearchScopeTab.entries.forEach { tab ->
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("search-scope-tab-${tab.name}")
                        .selectable(state.tab == tab, enabled = !state.finished, role = Role.RadioButton, onClick = { onTab(tab) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(state.tab == tab, null); Text(stringResource(if (tab == SearchScopeTab.Groups) R.string.group else R.string.book_source))
                    }
                }
            }
            if (state.tab == SearchScopeTab.Sources && state.searchExpanded) {
                OutlinedTextField(state.query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("search-scope-query"),
                    label = { Text(stringResource(R.string.screen)) }, singleLine = true, enabled = !state.finished)
            }
            val error = if (state.tab == SearchScopeTab.Groups) state.groupsError else state.sourcesError
            if (error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onRetry, Modifier.testTag("search-scope-retry")) { Text(stringResource(R.string.retry)) }
            }
            if (if (state.tab == SearchScopeTab.Groups) state.groupsLoading else state.sourcesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("search-scope-list")) {
                if (state.tab == SearchScopeTab.Groups) items(state.groups, key = { "group:$it" }) { group ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-scope-group-$group")
                        .toggleable(group in state.selectedGroups, enabled = !state.finished, role = Role.Checkbox, onValueChange = { onGroup(group) })
                        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(group in state.selectedGroups, null); Text(group, Modifier.weight(1f).padding(vertical = 12.dp))
                    }
                } else items(state.sources, key = { "source:${it.url}" }) { source ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-scope-source-${source.url}")
                        .selectable(state.selectedSource?.url == source.url, enabled = !state.finished, role = Role.RadioButton, onClick = { onSource(source.url) })
                        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(state.selectedSource?.url == source.url, null); Text(source.name, Modifier.weight(1f).padding(vertical = 12.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onAll, Modifier.testTag("search-scope-all"), enabled = !state.finished) { Text(stringResource(R.string.all_source)) }
                Spacer(Modifier.weight(1f))
                TextButton(onCancel, Modifier.testTag("search-scope-cancel"), enabled = !state.finished) { Text(stringResource(R.string.cancel)) }
                TextButton(onConfirm, Modifier.testTag("search-scope-confirm"), enabled = !state.finished) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}
