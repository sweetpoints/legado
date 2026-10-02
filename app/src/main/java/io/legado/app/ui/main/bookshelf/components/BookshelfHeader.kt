package io.legado.app.ui.main.bookshelf.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable fun BookshelfHeader(model: BookshelfHeaderModel, onContinue: () -> Unit,
    onRecentInfo: () -> Unit, modifier: Modifier = Modifier) {
    if (model.stats == null && model.recent == null) return
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            model.stats?.let { (books, reading) ->
                Text(stringResource(R.string.bookshelf_stats, books, reading), Modifier.testTag("shelf-stats"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            model.recent?.let { book ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shelf-continue")
                    .combinedClickable(onClick = onContinue, onLongClick = onRecentInfo),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.continue_read), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(book.name, Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(book.currentChapter.ifBlank { stringResource(R.string.read_not_started) }, Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(book.progressPercent ?: "0%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(painterResource(R.drawable.ic_arrow_right), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
