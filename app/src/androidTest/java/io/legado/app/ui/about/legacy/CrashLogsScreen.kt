package io.legado.app.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.components.LegadoTopAppBar

@Composable
fun CrashLogsScreen(
    state: CrashLogsUiState,
    onOpenLog: (String) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier) {
        Column {
            LegadoTopAppBar(
                title = stringResource(R.string.crash_log),
                onBack = onClose,
                backLabel = stringResource(R.string.close),
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    IconButton(
                        onClick = onClear,
                        enabled = !state.isBusy,
                        modifier = Modifier.testTag("crash-logs-clear"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_clear_all),
                            stringResource(R.string.clear),
                        )
                    }
                },
            )
            if (state.isBusy) {
                CircularProgressIndicator(Modifier.padding(16.dp).testTag("crash-logs-progress"))
            }
            state.error?.let { error ->
                Text(
                    error.ifBlank { stringResource(R.string.error) },
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("crash-logs-error"),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry, enabled = !state.isBusy) {
                    Text(stringResource(R.string.retry))
                }
            }
            if (state.logs.isEmpty() && !state.isBusy && state.error == null) {
                Text(
                    stringResource(R.string.empty),
                    Modifier.padding(24.dp).testTag("crash-logs-empty"),
                )
            }
            LazyColumn(
                modifier =
                    Modifier.fillMaxWidth().weight(1f, fill = false).testTag("crash-logs-list"),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(state.logs, key = { it.id }) { log ->
                    Text(
                        log.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !state.isBusy, onClick = { onOpenLog(log.id) })
                                .padding(16.dp)
                                .testTag("crash-log-${log.id}"),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
