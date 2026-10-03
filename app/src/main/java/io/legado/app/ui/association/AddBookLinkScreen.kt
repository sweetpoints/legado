package io.legado.app.ui.association

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable
internal fun AddBookLinkScreen(
    state: AddBookLinkState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp)
        ) {
            Text(
                stringResource(R.string.add_to_bookshelf),
                Modifier.fillMaxWidth().padding(18.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            if (state.loading)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("add-book-link-progress"))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onCancel,
                    enabled = !state.finished,
                    modifier = Modifier.testTag("add-book-link-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
