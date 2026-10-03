package io.legado.app.ui.association.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.association.AssociationPhase

/** The transparent host owns prompts; native child editors retain their existing public ABI. */
@Composable
fun AssociationImportScreen(
    state: AssociationImportState,
    configuredDirectory: String?,
    privateDirectory: String,
    onConfirmReadConfig: () -> Unit,
    onConfirmUnsupported: () -> Unit,
    onChooseSystemDirectory: () -> Unit,
    onChoosePrivateDirectory: () -> Unit,
    onCancelDirectory: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!state.loaded || state.busy || state.nativeResultPending)
            CircularProgressIndicator(Modifier.padding(24.dp))
        val session = state.session
        if (state.busy || state.nativeResultPending) return@Box
        when (session?.phase) {
            AssociationPhase.ReadConfig ->
                AssociationConfirmation(
                    title = stringResource(R.string.import_str),
                    message = stringResource(R.string.confirm_read_config_import),
                    onConfirm = onConfirmReadConfig,
                    onCancel = onClose,
                )
            AssociationPhase.Unsupported ->
                AssociationConfirmation(
                    title = stringResource(R.string.draw),
                    message =
                        stringResource(
                            R.string.file_not_supported,
                            session.unsupportedName.orEmpty(),
                        ),
                    onConfirm = onConfirmUnsupported,
                    onCancel = onClose,
                )
            AssociationPhase.Directory ->
                if (!session.choosingDirectory) {
                    AlertDialog(
                        onDismissRequest = onCancelDirectory,
                        title = { Text(stringResource(R.string.select_book_folder)) },
                        text = {
                            val message = stringResource(R.string.shared_local_books_storage)
                            Text(
                                if (session.importAfterDirectory) message
                                else "$message\n\n${configuredDirectory ?: privateDirectory}"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = onChooseSystemDirectory) {
                                Text(stringResource(R.string.select_folder))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = onChoosePrivateDirectory) {
                                Text(stringResource(R.string.shared_local_books_private))
                            }
                        },
                    )
                }
            else -> Unit
        }
        (state.restoreError ?: session?.error)?.let { error ->
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(stringResource(R.string.error)) },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.ok)) }
                },
            )
        }
    }
}

@Composable
private fun AssociationConfirmation(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}
