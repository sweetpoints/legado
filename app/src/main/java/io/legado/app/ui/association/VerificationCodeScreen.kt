package io.legado.app.ui.association

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.components.LegadoTopAppBar

internal data class VerificationImageState(
    val bitmap: Bitmap? = null,
    val previewSrc: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

@Composable
internal fun VerificationCodeScreen(
    state: VerificationCodeUiState,
    image: VerificationImageState,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDisable: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onShowImage: () -> Unit,
    onRetryImage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.busy && !state.closeRequested
    Surface(modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f)) {
        Column {
            LegadoTopAppBar(
                title = stringResource(R.string.input_verification_code),
                onBack = onClose,
                backLabel = stringResource(R.string.close),
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    IconButton(
                        onClick = onSubmit,
                        enabled = enabled,
                        modifier = Modifier.testTag("verification-submit"),
                    ) {
                        Icon(painterResource(R.drawable.ic_check), stringResource(R.string.ok))
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.disable_source)) },
                                onClick = {
                                    menuExpanded = false
                                    onDisable()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_source)) },
                                onClick = {
                                    menuExpanded = false
                                    onRequestDelete()
                                },
                            )
                        }
                    }
                },
            )
            Column(
                Modifier.weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.sourceName.isNotEmpty())
                    Text(state.sourceName, style = MaterialTheme.typography.titleSmall)
                Box(
                    Modifier.fillMaxWidth()
                        .height(100.dp)
                        .testTag("verification-image")
                        .clickable(
                            enabled = image.bitmap != null && image.previewSrc != null,
                            onClick = onShowImage,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        image.bitmap != null ->
                            Image(
                                bitmap = image.bitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.verification_code),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        image.loading -> CircularProgressIndicator()
                        else ->
                            Image(
                                painterResource(R.drawable.image_loading_error),
                                stringResource(R.string.error),
                            )
                    }
                }
                if (image.error != null) {
                    Text(image.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetryImage) { Text(stringResource(R.string.retry)) }
                }
                OutlinedTextField(
                    value = state.code,
                    onValueChange = onCodeChanged,
                    label = { Text(stringResource(R.string.verification_code)) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("verification-code"),
                )
                if (state.busy) CircularProgressIndicator()
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (state.deleteConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.draw)) },
            text = { Text(stringResource(R.string.sure_del) + "\n" + state.sourceName) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) { Text(stringResource(R.string.no)) }
            },
        )
    }
}
