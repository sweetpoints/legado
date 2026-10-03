package io.legado.app.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import io.legado.app.R

/** The password draft belongs to this open prompt and is never placed in saved instance state. */
@Composable
internal fun MainLocalPasswordScreen(
    onConfirm: (String) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(TextFieldValue()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_local_password)) },
        text = {
            Column {
                Text(stringResource(R.string.set_local_password_summary))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().testTag("main-local-password-input"),
                    placeholder = { Text("password") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft.text) },
                modifier = Modifier.testTag("main-local-password-confirm"),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.testTag("main-local-password-skip"),
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
