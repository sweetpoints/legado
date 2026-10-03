package io.legado.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.cover.*
import kotlinx.coroutines.launch

internal data class CoverSettingsActions(
    val boolean: (CoverSettingSwitch, Boolean) -> Unit,
    val image: (CoverSettingImage) -> Unit,
    val picker: (CoverSettingImage) -> Unit,
    val remove: (CoverSettingImage) -> Unit,
    val destination: (CoverDestination) -> Unit,
    val dismiss: () -> Unit,
    val retry: () -> Unit,
)

private data class CoverRow(
    val key: String,
    val title: String,
    val summary: String = "",
    val category: String = "",
    val boolean: CoverSettingSwitch? = null,
    val image: CoverSettingImage? = null,
    val destination: CoverDestination? = null,
    val heading: Boolean = false,
)

@Composable
private fun coverRows(settings: CoverSettingsSnapshot): List<CoverRow> {
    val rows =
        mutableListOf(
            CoverRow(
                CoverSettingSwitch.Wifi.key,
                stringResource(R.string.only_wifi),
                stringResource(R.string.only_wifi_summary),
                boolean = CoverSettingSwitch.Wifi,
            ),
            CoverRow(
                "rules",
                stringResource(R.string.cover_rule),
                stringResource(R.string.cover_rule_summary),
                destination = CoverDestination.Rules,
            ),
            CoverRow(
                CoverSettingSwitch.Default.key,
                stringResource(R.string.use_default_cover),
                stringResource(R.string.use_default_cover_s),
                boolean = CoverSettingSwitch.Default,
            ),
            CoverRow(
                "font",
                stringResource(R.string.cover_font_config),
                destination = CoverDestination.Font,
            ),
        )
    listOf(false, true).forEach { night ->
        val category = stringResource(if (night) R.string.night else R.string.day)
        val image = if (night) CoverSettingImage.Night else CoverSettingImage.Day
        rows +=
            CoverRow(
                if (night) "night-category" else "day-category",
                category,
                category = category,
                heading = true,
            )
        rows +=
            CoverRow(
                image.key,
                stringResource(R.string.default_cover),
                settings.images.getValue(image).ifEmpty { stringResource(R.string.select_image) },
                category,
                image = image,
            )
        val name = if (night) CoverSettingSwitch.NightName else CoverSettingSwitch.DayName
        val author = if (night) CoverSettingSwitch.NightAuthor else CoverSettingSwitch.DayAuthor
        rows +=
            CoverRow(
                name.key,
                stringResource(R.string.cover_show_name),
                stringResource(R.string.cover_show_name_summary),
                category,
                boolean = name,
            )
        rows +=
            CoverRow(
                author.key,
                stringResource(R.string.cover_show_author),
                stringResource(R.string.cover_show_author_summary),
                category,
                boolean = author,
            )
    }
    val category = stringResource(R.string.read_record_covers)
    rows += CoverRow("records-category", category, category = category, heading = true)
    listOf(CoverSettingImage.RecordDay, CoverSettingImage.RecordNight).forEach { key ->
        rows +=
            CoverRow(
                key.key,
                stringResource(
                    if (key == CoverSettingImage.RecordDay) R.string.read_record_cover_day
                    else R.string.read_record_cover_night
                ),
                settings.images.getValue(key).ifEmpty { stringResource(R.string.select_image) },
                category,
                image = key,
            )
    }
    return rows
}

@Composable
internal fun CoverSettingsScreen(
    state: CoverSettingsState,
    actions: CoverSettingsActions,
    search: String? = null,
    searchFinished: () -> Unit = {},
    searchEmpty: () -> Unit = {},
) {
    val settings = state.settings ?: CoverSettingsSnapshot()
    val rows = coverRows(settings)
    val enabled = !state.loading && !state.failed && !state.busy
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val found =
        search
            ?.let { query ->
                rows.filter {
                    !it.heading &&
                        configPreferenceMatches(query, it.title, it.summary, listOf(it.category))
                }
            }
            .orEmpty()
    LaunchedEffect(search, state.loading, state.failed) {
        if (search != null && !state.loading && !state.failed && found.isEmpty()) searchEmpty()
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            state = scroll,
            modifier = Modifier.fillMaxSize().testTag("cover-settings-list"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (state.loading || state.busy)
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error ->
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("cover-settings-error"),
                        )
                        TextButton(
                            onClick = actions.retry,
                            enabled = !state.loading && !state.busy,
                            modifier =
                                Modifier.heightIn(min = 48.dp).testTag("cover-settings-retry"),
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
            items(rows, key = { it.key }) { row ->
                val canEdit = enabled && (row.boolean?.let(settings::enabled) ?: true)
                val base =
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cover-row-${row.key}")
                val input =
                    when {
                        row.boolean != null ->
                            base.toggleable(
                                settings.switches.getValue(row.boolean),
                                enabled = canEdit,
                                role = Role.Switch,
                            ) {
                                actions.boolean(row.boolean, it)
                            }
                        row.image != null ->
                            base.clickable(enabled = canEdit) { actions.image(row.image) }
                        row.destination != null ->
                            base.clickable(enabled = canEdit) {
                                actions.destination(row.destination)
                            }
                        else -> base
                    }
                Row(
                    input.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.title,
                            color =
                                if (row.heading) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            style =
                                if (row.heading) MaterialTheme.typography.titleSmall
                                else MaterialTheme.typography.bodyLarge,
                        )
                        if (row.summary.isNotEmpty())
                            Text(row.summary, style = MaterialTheme.typography.bodySmall)
                    }
                    row.boolean?.let {
                        Switch(
                            checked = settings.switches.getValue(it),
                            onCheckedChange = null,
                            enabled = canEdit,
                        )
                    }
                }
            }
        }
        if (search != null && found.isNotEmpty())
            AlertDialog(
                onDismissRequest = searchFinished,
                title = { Text(search) },
                text = {
                    LazyColumn {
                        items(found, key = { it.key }) { row ->
                            Text(
                                listOf(row.category, row.title)
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" > "),
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("cover-search-${row.key}")
                                    .clickable {
                                        searchFinished()
                                        scope.launch {
                                            scroll.animateScrollToItem(
                                                rows.indexOf(row) +
                                                    (if (state.loading || state.busy) 1 else 0) +
                                                    (if (state.error != null) 1 else 0)
                                            )
                                        }
                                    }
                                    .padding(16.dp),
                            )
                        }
                    }
                },
                confirmButton = {},
            )
        state.popup?.let { key ->
            AlertDialog(
                onDismissRequest = actions.dismiss,
                title = { Text(stringResource(R.string.default_cover)) },
                text = {
                    Column {
                        TextButton(
                            onClick = { actions.remove(key) },
                            enabled = enabled,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("cover-image-delete"),
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                        TextButton(
                            onClick = { actions.picker(key) },
                            enabled = enabled,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("cover-image-select"),
                        ) {
                            Text(stringResource(R.string.select_image))
                        }
                    }
                },
                confirmButton = {},
            )
        }
    }
}
