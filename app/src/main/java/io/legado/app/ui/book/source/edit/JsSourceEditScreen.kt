package io.legado.app.ui.book.source.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable
internal fun JsSourceEditScreen(
    state: JsSourceEditState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.js_source_edit),
                style = MaterialTheme.typography.titleLarge,
            )
            if (state.error == null) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp).testTag("js-source-working")
                )
            }
            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("js-source-error"),
                )
                TextButton(
                    onClick = onRetry,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("js-source-retry"),
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
            TextButton(
                onClick = onCancel,
                enabled = !state.busy,
                modifier = Modifier.testTag("js-source-cancel"),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
