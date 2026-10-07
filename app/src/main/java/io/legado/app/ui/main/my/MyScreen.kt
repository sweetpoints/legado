package io.legado.app.ui.main.my

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.ui.components.SettingsCategoryHeader
import io.legado.app.ui.theme.LocalLegadoColors

@Composable
fun MyScreen(
    state: MyUiState,
    isMore: Boolean,
    onItemClick: (String) -> Unit,
    onSwitchChange: (String, Boolean) -> Unit,
    onLongClick: (String) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onCustomize: () -> Unit,
    onCustomizationToggle: (String) -> Unit,
    onCustomizationConfirm: () -> Unit,
    onCustomizationDismiss: () -> Unit,
    onHelp: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var themePickerVisible by rememberSaveable { mutableStateOf(false) }
    val modes = stringArrayResource(R.array.theme_mode)
    val values = stringArrayResource(R.array.theme_mode_v)
    val rows =
        visibleMySettings(state.preferences.moreItems, isMore)
            .map { item ->
                val checked =
                    when (item.key) {
                        PreferKey.webService -> state.preferences.webEnabled
                        PreferKey.mcpService -> state.preferences.mcpEnabled
                        PreferKey.autoTaskService -> state.preferences.autoTaskEnabled
                        else -> false
                    }
                val summary =
                    when {
                        item.key == PreferKey.webService && checked -> state.webAddress
                        item.key == PreferKey.mcpService && checked -> state.mcpAddress
                        else -> item.summaryRes?.let { stringResource(it) }
                    }
                MyRow(
                    item,
                    stringResource(item.titleRes),
                    summary,
                    item.categoryRes?.let { stringResource(it) },
                    checked,
                    if (item.kind == MySettingKind.ThemeChoice)
                        modes.getOrNull(values.indexOf(state.preferences.themeMode))
                    else null,
                )
            }
            .filter { row ->
                query.isBlank() ||
                    listOfNotNull(row.title, row.summary, row.category).any {
                        it.contains(query.trim(), ignoreCase = true)
                    }
            }

    Column(modifier.fillMaxSize()) {
        MyTopBar(isMore, onBack, onCustomize, onHelp)
        if (isMore) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f)
                .fillMaxWidth()
                .testTag(if (isMore) "my-more-settings-list" else "my-settings-list")
        ) {
            var lastCategory: Int? = null
            rows.forEach { row ->
                if (row.item.categoryRes != null && row.item.categoryRes != lastCategory) {
                    item(key = "category-${row.item.categoryRes}") {
                        SettingsCategoryHeader(row.category.orEmpty())
                    }
                }
                lastCategory = row.item.categoryRes
                item(key = row.item.key) {
                    MySettingsRow(
                        row,
                        isMore,
                        onClick = {
                            when (row.item.kind) {
                                MySettingKind.Switch -> onSwitchChange(row.item.key, !row.checked)
                                MySettingKind.ThemeChoice -> themePickerVisible = true
                                MySettingKind.Action -> onItemClick(row.item.key)
                            }
                        },
                        onLongClick = { onLongClick(row.item.key) },
                    )
                }
            }
        }
    }
    state.customizationDraft?.let { draft ->
        MyCustomizationDialog(
            draft,
            onCustomizationToggle,
            onCustomizationConfirm,
            onCustomizationDismiss,
        )
    }
    if (themePickerVisible) {
        AlertDialog(
            onDismissRequest = { themePickerVisible = false },
            title = { Text(stringResource(R.string.theme_mode)) },
            text = {
                Column {
                    modes.forEachIndexed { index, label ->
                        TextButton(
                            onClick = {
                                themePickerVisible = false
                                values.getOrNull(index)?.let(onThemeModeChange)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { themePickerVisible = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

private data class MyRow(
    val item: MySettingItem,
    val title: String,
    val summary: String?,
    val category: String?,
    val checked: Boolean,
    val choiceLabel: String?,
)

@Composable
private fun MySettingsRow(
    row: MyRow,
    isMore: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalLegadoColors.current
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 60.dp)
            .testTag(
                if (isMore) "my-more-setting-${row.item.key}" else "my-setting-${row.item.key}"
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) {
                if (row.item.kind == MySettingKind.Switch) {
                    role = Role.Switch
                    toggleableState = if (row.checked) ToggleableState.On else ToggleableState.Off
                } else role = Role.Button
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(row.item.iconRes), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.secondary)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                row.title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.summary
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
        }
        when (row.item.kind) {
            MySettingKind.Switch -> Switch(row.checked, onCheckedChange = null)
            MySettingKind.ThemeChoice ->
                row.choiceLabel?.let { Text(it, color = colors.textSecondary) }
            else -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyTopBar(
    isMore: Boolean,
    onBack: () -> Unit,
    onCustomize: () -> Unit,
    onHelp: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(if (isMore) R.string.reader_menu_more else R.string.my)) },
        navigationIcon = {
            if (isMore)
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                }
        },
        actions = {
            IconButton(onClick = onCustomize) {
                Icon(
                    painterResource(R.drawable.ic_more_vert),
                    stringResource(R.string.customize_my),
                )
            }
            IconButton(onClick = onHelp) {
                Icon(painterResource(R.drawable.ic_help), stringResource(R.string.help))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(),
    )
}

@Composable
private fun MyCustomizationDialog(
    draft: Set<String>,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.my_more_items)) },
        text = {
            LazyColumn(Modifier.testTag("my-customization-list")) {
                items(customizableMySettings, key = { it.key }) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("my-option-${item.key}")
                            .combinedClickable(onClick = { onToggle(item.key) })
                            .semantics(mergeDescendants = true) {
                                role = Role.Checkbox
                                toggleableState =
                                    if (item.key in draft) ToggleableState.On
                                    else ToggleableState.Off
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(item.key in draft, onCheckedChange = null)
                        Text(
                            stringResource(item.titleRes),
                            Modifier.weight(1f).padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
