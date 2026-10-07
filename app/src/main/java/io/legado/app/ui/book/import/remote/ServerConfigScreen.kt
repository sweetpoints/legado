package io.legado.app.ui.book.import.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.legado.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerConfigScreen(
    state: ServerConfigUiState,
    onEdit: (ServerConfigField, String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (!state.saving) onClose() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp)) {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.server_config),
                        Modifier,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClose,
                        enabled = !state.saving,
                        modifier = Modifier.testTag("server-close"),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                actions = {
                    TextButton(
                        onSave,
                        enabled =
                            !state.loading && !state.loadFailed && !state.saving && !state.finished,
                        modifier = Modifier.testTag("server-save"),
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                windowInsets = WindowInsets(0),
            )
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.loadFailed)
                TextButton(onRetry, Modifier.testTag("server-retry")) {
                    Text(stringResource(R.string.retry))
                }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                        ServerConfigField.Name to state.draft.name,
                        ServerConfigField.Url to state.draft.url,
                        ServerConfigField.Username to state.draft.username,
                        ServerConfigField.Password to state.draft.password,
                    )
                    .forEach { (field, value) ->
                        var text by
                            rememberSaveable(field, stateSaver = TextFieldValue.Saver) {
                                mutableStateOf(TextFieldValue(value))
                            }
                        LaunchedEffect(value) {
                            if (text.text != value)
                                text =
                                    TextFieldValue(
                                        value,
                                        TextRange(
                                            text.selection.start.coerceAtMost(value.length),
                                            text.selection.end.coerceAtMost(value.length),
                                        ),
                                    )
                        }
                        OutlinedTextField(
                            text,
                            {
                                text = it
                                onEdit(field, it.text)
                            },
                            enabled =
                                !state.loading &&
                                    !state.loadFailed &&
                                    !state.saving &&
                                    !state.finished,
                            label = {
                                Text(
                                    if (field == ServerConfigField.Name)
                                        stringResource(R.string.name)
                                    else field.name.lowercase()
                                )
                            },
                            singleLine = true,
                            modifier =
                                Modifier.fillMaxWidth().testTag("server-field-${field.name}"),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType =
                                        when (field) {
                                            ServerConfigField.Url -> KeyboardType.Uri
                                            ServerConfigField.Password -> KeyboardType.Password
                                            else -> KeyboardType.Text
                                        }
                                ),
                            visualTransformation =
                                if (field == ServerConfigField.Password && !passwordVisible)
                                    PasswordVisualTransformation()
                                else VisualTransformation.None,
                            trailingIcon = {
                                if (field == ServerConfigField.Password)
                                    TextButton(
                                        { passwordVisible = !passwordVisible },
                                        Modifier.testTag("server-password-visibility"),
                                    ) {
                                        Icon(
                                            painterResource(
                                                if (passwordVisible)
                                                    com.google.android.material.R.drawable
                                                        .design_ic_visibility_off
                                                else
                                                    com.google.android.material.R.drawable
                                                        .design_ic_visibility
                                            ),
                                            stringResource(
                                                if (passwordVisible)
                                                    R.string.source_login_hide_password
                                                else R.string.source_login_show_password
                                            ),
                                        )
                                    }
                            },
                        )
                    }
                Text("TYPE: WEBDAV", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
