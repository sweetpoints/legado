package io.legado.app.ui.main.bookshelf.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable internal fun BookshelfAddProgressScreen(progress: Int, onCancel: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.bookshelf_adding_progress, progress.coerceAtLeast(0)), Modifier.testTag("shelf-add-progress"))
            }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End).testTag("shelf-add-cancel")) { Text(stringResource(R.string.cancel)) }
        }
    }
}
