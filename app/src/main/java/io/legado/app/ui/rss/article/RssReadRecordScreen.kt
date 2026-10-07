package io.legado.app.ui.rss.article

import android.util.Patterns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable
internal fun RssReadRecordScreen(
    state: RssReadRecordState,
    read: (String) -> Unit,
    browser: (String) -> Unit,
    requestClear: () -> Unit,
    confirmClear: () -> Unit,
    cancelClear: () -> Unit,
    close: () -> Unit,
    retry: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
                .testTag("rss-read-record")
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.read_record),
                        Modifier.weight(1f).padding(vertical = 16.dp).testTag("rss-history-title"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(
                        onClick = requestClear,
                        enabled = state.canAct,
                        modifier = Modifier.testTag("rss-history-clear"),
                    ) {
                        Text(
                            stringResource(R.string.clear),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (state.loading || state.busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("rss-history-working"))
            state.error?.let {
                Text(
                    it,
                    Modifier.padding(16.dp).testTag("rss-history-error"),
                    color = MaterialTheme.colorScheme.error,
                )
                if (!state.loaded && !state.loading)
                    TextButton(onClick = retry, modifier = Modifier.testTag("rss-history-retry")) {
                        Text(stringResource(R.string.retry))
                    }
            }
            // Delay creation of saveable LazyListState until records exist, preserving restored
            // scroll.
            if (state.loaded)
                LazyColumn(Modifier.weight(1f, fill = false).testTag("rss-history-list")) {
                    items(state.items, key = { it.key }) { item ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(enabled = state.canAct, onClick = { read(item.key) })
                                    .horizontalScroll(rememberScrollState())
                                    .testTag("rss-history-read-${item.key}"),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    item.title.ifBlank { item.record },
                                    Modifier.padding(vertical = 8.dp),
                                    maxLines = 1,
                                )
                            }
                            val currentBrowser by rememberUpdatedState(browser)
                            val linkColor = MaterialTheme.colorScheme.secondary
                            val text =
                                remember(item.key, item.record, linkColor, state.canAct) {
                                    buildAnnotatedString {
                                        if (
                                            Patterns.WEB_URL.matcher(item.record).matches() &&
                                                state.canAct
                                        )
                                            withLink(
                                                LinkAnnotation.Url(
                                                    item.record,
                                                    TextLinkStyles(
                                                        SpanStyle(
                                                            color = linkColor,
                                                            textDecoration =
                                                                TextDecoration.Underline,
                                                        )
                                                    ),
                                                    LinkInteractionListener {
                                                        currentBrowser(item.key)
                                                    },
                                                )
                                            ) {
                                                append(item.record)
                                            }
                                        else append(item.record)
                                    }
                                }
                            Box(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .horizontalScroll(rememberScrollState()),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text,
                                    Modifier.padding(vertical = 8.dp)
                                        .testTag("rss-history-url-${item.key}"),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            else Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = close,
                enabled = !(state.busy && state.clearCount != null),
                modifier = Modifier.align(Alignment.End).testTag("rss-history-close"),
            ) {
                Text(stringResource(R.string.text_return))
            }
        }
    }
    state.clearCount?.let { count ->
        AlertDialog(
            onDismissRequest = cancelClear,
            title = { Text(stringResource(R.string.draw)) },
            text = {
                Text(
                    stringResource(R.string.sure_del) +
                        "\n$count " +
                        stringResource(R.string.read_record),
                    Modifier.testTag("rss-history-clear-count"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = confirmClear,
                    enabled = !state.busy && !state.loading,
                    modifier = Modifier.testTag("rss-history-clear-confirm"),
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = cancelClear,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("rss-history-clear-cancel"),
                ) {
                    Text(stringResource(R.string.no))
                }
            },
        )
    }
}
