package io.legado.app.ui.book.read

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatedReadingScreen(
    state: SimulatedReadingState,
    enabled: (Boolean) -> Unit,
    start: (String) -> Unit,
    daily: (String) -> Unit,
    date: (String) -> Unit,
    dateOpen: (Boolean) -> Unit,
    save: () -> Unit,
    cancel: () -> Unit,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = !state.loading && !state.saving && !state.finished
    Column(
        modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.simulated_reading),
            style = MaterialTheme.typography.titleLarge,
        )
        if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.settings?.let { value ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.switch_on))
                Switch(
                    value.enabled,
                    enabled,
                    enabled = ready,
                    modifier = Modifier.testTag("simulation-enabled"),
                )
            }
            Text(stringResource(R.string.start_from))
            TextButton(
                { dateOpen(true) },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().testTag("simulation-date"),
            ) {
                Text(value.date)
            }
            OutlinedTextField(
                value.start,
                start,
                label = { Text(stringResource(R.string.start_chapter)) },
                singleLine = true,
                enabled = ready,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("simulation-start"),
            )
            OutlinedTextField(
                value.daily,
                daily,
                label = { Text(stringResource(R.string.daily_chapters)) },
                singleLine = true,
                enabled = ready,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("simulation-daily"),
            )
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(retry, enabled = ready, modifier = Modifier.testTag("simulation-retry")) {
                Text(stringResource(R.string.retry))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                cancel,
                enabled = !state.saving,
                modifier = Modifier.testTag("simulation-cancel"),
            ) {
                Text(stringResource(R.string.cancel))
            }
            TextButton(
                save,
                enabled = ready && state.settings != null && state.error == null,
                modifier = Modifier.testTag("simulation-save"),
            ) {
                Text(stringResource(R.string.ok))
            }
        }
    }
    if (state.dateOpen && state.settings != null) {
        val initial = runCatching {
            LocalDate.parse(state.settings.date)
        }
            .getOrElse { LocalDate.now() }
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
        DatePickerDialog(
            onDismissRequest = { dateOpen(false) },
            confirmButton = {
                TextButton(
                    {
                        picker.selectedDateMillis?.let {
                            date(
                                java.time.Instant.ofEpochMilli(it)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toString()
                            )
                        }
                    },
                    enabled = picker.selectedDateMillis != null,
                    modifier = Modifier.testTag("simulation-date-confirm"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    { dateOpen(false) },
                    modifier = Modifier.testTag("simulation-date-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(picker)
        }
    }
}
