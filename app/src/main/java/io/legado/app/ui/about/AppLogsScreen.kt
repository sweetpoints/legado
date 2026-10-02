package io.legado.app.ui.about

import android.util.Patterns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.AppLogRow
import io.legado.app.ui.components.LegadoTopAppBar

@Composable
fun AppLogsScreen(
    state: AppLogsUiState,
    onOpenLog: (Long) -> Unit,
    onRequestClear: () -> Unit,
    onConfirmClear: () -> Unit,
    onDismissClear: () -> Unit,
    onExport: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(modifier = modifier) {
        Column {
            LegadoTopAppBar(
                title = stringResource(R.string.log),
                onBack = onClose,
                backLabel = stringResource(R.string.close),
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    IconButton(onClick = onRequestClear, enabled = !state.isBusy,
                        modifier = Modifier.testTag("app-logs-clear")) {
                        Icon(painterResource(R.drawable.ic_clear_all), stringResource(R.string.clear))
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }, enabled = !state.isBusy,
                            modifier = Modifier.testTag("app-logs-menu")) {
                            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_menu))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_export), null) },
                                enabled = !state.isBusy,
                                onClick = { menuExpanded = false; onExport() },
                            )
                        }
                    }
                },
            )
            if (state.isBusy) {
                CircularProgressIndicator(Modifier.padding(16.dp).testTag("app-logs-progress"))
            }
            state.error?.let { error ->
                Text(error.ifBlank { stringResource(R.string.error) },
                    Modifier.padding(16.dp).testTag("app-logs-error"), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, enabled = !state.isBusy) {
                    Text(stringResource(R.string.retry))
                }
            }
            state.notice?.let { notice ->
                Text(stringResource(when (notice) {
                    AppLogNotice.NoLogs -> R.string.no_log
                    AppLogNotice.ShareFailed -> R.string.can_not_share
                }), Modifier.padding(16.dp).testTag("app-logs-notice"))
            }
            if (state.logs.isEmpty() && !state.isBusy && state.error == null && state.notice == null) {
                Text(stringResource(R.string.no_log), Modifier.padding(24.dp).testTag("app-logs-empty"))
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("app-logs-list"),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(state.logs, key = { it.id }) { log ->
                    AppLogItem(log, !state.isBusy, onOpenLog)
                    HorizontalDivider()
                }
            }
        }
    }
    if (state.showClearConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissClear,
            title = { Text(stringResource(R.string.clear)) },
            text = { Text(stringResource(R.string.clear_log_confirm)) },
            confirmButton = {
                TextButton(onClick = onConfirmClear, enabled = !state.isBusy) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissClear) { Text(stringResource(R.string.no)) }
            },
        )
    }
}

@Composable
private fun AppLogItem(log: AppLogRow, enabled: Boolean, onOpenLog: (Long) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val message = remember(log.message, linkColor) {
        buildAnnotatedString {
            append(log.message)
            val matcher = Patterns.WEB_URL.matcher(log.message)
            while (matcher.find()) {
                val link = matcher.group().orEmpty()
                val url = if (link.contains("://")) link else "https://$link"
                addLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor))),
                    matcher.start(), matcher.end())
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("app-log-${log.id}")
        .then(if (log.hasDetails) Modifier.clickable(enabled = enabled) { onOpenLog(log.id) } else Modifier)
        .padding(16.dp)) {
        Text(log.time, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
