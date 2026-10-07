package io.legado.app.ui.book.changecover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.ChangeCoverStatus
import io.legado.app.data.repository.CoverRequest
import io.legado.app.ui.components.cover.ComposeCover

@Composable
internal fun ChangeCoverScreen(
    state: ChangeCoverState,
    onStartStop: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    cover: @Composable (CoverRequest, Modifier) -> Unit = { request, size ->
        ComposeCover(request, size)
    },
) {
    Surface(modifier) {
        Column(Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClose, Modifier.testTag("change-cover-close")) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_close),
                            stringResource(R.string.close),
                        )
                    }
                    Text(
                        stringResource(R.string.change_cover_source),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val status = state.snapshot?.status ?: ChangeCoverStatus.Idle
                    val label =
                        when (status) {
                            ChangeCoverStatus.Running -> R.string.stop
                            ChangeCoverStatus.RuleReady -> R.string.resume
                            ChangeCoverStatus.Idle -> R.string.refresh
                        }
                    val icon =
                        when (status) {
                            ChangeCoverStatus.Running -> R.drawable.ic_stop_black_24dp
                            ChangeCoverStatus.RuleReady -> R.drawable.ic_play_outline_24dp
                            ChangeCoverStatus.Idle -> R.drawable.ic_refresh_black_24dp
                        }
                    IconButton(
                        onStartStop,
                        Modifier.testTag("change-cover-start-stop"),
                        enabled = !state.loading && !state.finished && state.snapshot != null,
                    ) {
                        Icon(painterResource(icon), stringResource(label))
                    }
                }
            }
            if (state.loading || state.snapshot?.status == ChangeCoverStatus.Running)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("change-cover-progress"))
            state.error?.let { error ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(
                        onRetry,
                        Modifier.testTag("change-cover-retry"),
                        enabled = !state.loading,
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            val snapshot = state.snapshot
            LazyVerticalGrid(
                GridCells.Fixed(3),
                Modifier.weight(1f).fillMaxWidth().testTag("change-cover-grid"),
            ) {
                items(snapshot?.covers.orEmpty(), key = { it.id }) { item ->
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("change-cover-item-${item.id}")
                            .clickable(enabled = !state.finished, onClick = { onSelect(item.id) })
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        cover(
                            CoverRequest(
                                path = item.coverUrl.takeUnless { it == "use_default_cover" },
                                name = snapshot?.target?.name,
                                author = snapshot?.target?.author,
                                loadOnlyWifi = false,
                                sourceOrigin = item.origin,
                            ),
                            Modifier.fillMaxWidth(),
                        )
                        Text(
                            if (item.id == "default") stringResource(R.string.default_cover)
                            else item.originName,
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
