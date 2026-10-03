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
import io.legado.app.model.welcome.*
import kotlinx.coroutines.launch

internal data class WelcomeSettingsActions(
    val milliseconds: (Int) -> Unit,
    val step: (Int) -> Unit,
    val boolean: (WelcomeSwitch, Boolean) -> Unit,
    val image: (Boolean) -> Unit,
    val picker: (Boolean) -> Unit,
    val removeImage: (Boolean) -> Unit,
    val dismiss: () -> Unit,
    val retry: () -> Unit,
)

private data class WelcomeRow(
    val key: String,
    val title: String,
    val summary: String = "",
    val category: String = "",
    val boolean: WelcomeSwitch? = null,
    val night: Boolean? = null,
    val heading: Boolean = false,
)

@Composable
private fun welcomeRows(value: WelcomeSettingsSnapshot): List<WelcomeRow> {
    val rows =
        mutableListOf(
            WelcomeRow(
                "time",
                stringResource(R.string.welcome_show_time),
                stringResource(R.string.welcome_show_time_summary),
            ),
            WelcomeRow(
                WelcomeSwitch.Custom.key,
                stringResource(R.string.custom_welcome),
                stringResource(R.string.custom_welcome_summary),
                boolean = WelcomeSwitch.Custom,
            ),
        )
    listOf(false, true).forEach { night ->
        val category = stringResource(if (night) R.string.night else R.string.day)
        rows +=
            WelcomeRow(
                if (night) "night-category" else "day-category",
                category,
                category = category,
                heading = true,
            )
        rows +=
            WelcomeRow(
                if (night) "night-image" else "day-image",
                stringResource(R.string.background_image),
                value.image(night).ifEmpty { stringResource(R.string.select_image) },
                category,
                night = night,
            )
        rows +=
            WelcomeRow(
                (if (night) WelcomeSwitch.NightText else WelcomeSwitch.DayText).key,
                stringResource(R.string.show_welcome_text),
                stringResource(R.string.welcome_text),
                category,
                boolean = if (night) WelcomeSwitch.NightText else WelcomeSwitch.DayText,
            )
        rows +=
            WelcomeRow(
                (if (night) WelcomeSwitch.NightIcon else WelcomeSwitch.DayIcon).key,
                stringResource(R.string.show_icon),
                stringResource(R.string.show_default_book_icon),
                category,
                boolean = if (night) WelcomeSwitch.NightIcon else WelcomeSwitch.DayIcon,
            )
    }
    return rows
}

@Composable
internal fun WelcomeSettingsScreen(
    state: WelcomeSettingsState,
    actions: WelcomeSettingsActions,
    search: String? = null,
    searchFinished: () -> Unit = {},
    searchEmpty: () -> Unit = {},
) {
    val enabled = !state.loading && !state.failed && !state.busy
    val rows = welcomeRows(state.settings ?: WelcomeSettingsSnapshot())
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
            modifier = Modifier.fillMaxSize().testTag("welcome-settings-list"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (state.loading || state.busy)
                item {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().testTag("welcome-settings-progress")
                    )
                }
            state.error?.let { error ->
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("welcome-settings-error"),
                        )
                        TextButton(
                            onClick = actions.retry,
                            enabled = !state.loading && !state.busy,
                            modifier =
                                Modifier.heightIn(min = 48.dp).testTag("welcome-settings-retry"),
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
            items(rows, key = { it.key }) { row ->
                val selected = row.boolean?.let { state.settings?.switches?.get(it) ?: it.default }
                val base =
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("welcome-row-${row.key}")
                val input =
                    when {
                        row.boolean != null ->
                            base.toggleable(
                                selected == true,
                                enabled = enabled,
                                role = Role.Switch,
                            ) {
                                actions.boolean(row.boolean, it)
                            }
                        row.night != null ->
                            base.clickable(enabled = enabled) { actions.image(row.night) }
                        else -> base
                    }
                Column(input.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        if (selected != null)
                            Switch(checked = selected, onCheckedChange = null, enabled = enabled)
                    }
                    if (row.key == "time") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { actions.step(-1) },
                                enabled = enabled && state.milliseconds > 0,
                                modifier =
                                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("welcome-time-minus"),
                            ) {
                                Text("−")
                            }
                            Text(
                                "${state.milliseconds} ms",
                                Modifier.weight(1f).testTag("welcome-time-value"),
                            )
                            TextButton(
                                onClick = { actions.step(1) },
                                enabled = enabled && state.milliseconds < 800,
                                modifier =
                                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("welcome-time-plus"),
                            ) {
                                Text("+")
                            }
                        }
                        Slider(
                            value = state.milliseconds.toFloat(),
                            onValueChange = { actions.milliseconds(it.toInt()) },
                            valueRange = 0f..800f,
                            enabled = enabled,
                            modifier = Modifier.testTag("welcome-time-slider"),
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
                        items(found, key = { "search-${it.key}" }) { row ->
                            Text(
                                listOf(row.category, row.title)
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" > "),
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("welcome-search-${row.key}")
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
        state.popupNight?.let { night ->
            AlertDialog(
                onDismissRequest = actions.dismiss,
                title = { Text(stringResource(R.string.background_image)) },
                text = {
                    Column {
                        TextButton(
                            onClick = { actions.removeImage(night) },
                            enabled = enabled,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("welcome-image-delete"),
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                        TextButton(
                            onClick = { actions.picker(night) },
                            enabled = enabled,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("welcome-image-select"),
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
