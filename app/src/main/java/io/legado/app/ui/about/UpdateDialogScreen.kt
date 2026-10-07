package io.legado.app.ui.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.MarkdownImageRepository
import io.legado.app.ui.components.markdown.SearchableRichText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateDialogScreen(
    state: UpdateDialogState,
    images: MarkdownImageRepository,
    download: (UpdateDownloadTarget) -> Unit,
    browser: () -> Unit,
    ignore: () -> Unit,
    cancel: () -> Unit,
    retry: () -> Unit,
    link: (String) -> Unit,
    image: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().testTag("update-dialog")) {
            TopAppBar(
                title = {
                    Column(Modifier) {
                        Text(
                            state.request?.version.orEmpty(),
                            Modifier.testTag("update-title"),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (state.metadata.isNotBlank())
                            Text(
                                state.metadata,
                                Modifier.testTag("update-metadata"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                },
                actions = {
                    Box {
                        TextButton(
                            onClick = { menu = true },
                            enabled = state.canAct,
                            modifier = Modifier.testTag("update-menu"),
                        ) {
                            Text(
                                stringResource(R.string.more_menu),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open_in_browser)) },
                                onClick = {
                                    menu = false
                                    browser()
                                },
                                modifier = Modifier.testTag("update-browser"),
                            )
                            state.downloadTargets.forEach { target ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                when (target) {
                                                    UpdateDownloadTarget.Backup ->
                                                        R.string.download_from_github
                                                    UpdateDownloadTarget.Mirror ->
                                                        R.string.download_from_backup_cdn
                                                    UpdateDownloadTarget.AlternateMirror ->
                                                        R.string.download_from_second_cdn
                                                    UpdateDownloadTarget.Primary ->
                                                        R.string.action_download
                                                }
                                            )
                                        )
                                    },
                                    onClick = {
                                        menu = false
                                        download(target)
                                    },
                                    modifier = Modifier.testTag("update-download-${target.name}"),
                                )
                            }
                            if (state.request?.beta == false)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ignore_this_version)) },
                                    onClick = {
                                        menu = false
                                        ignore()
                                    },
                                    modifier = Modifier.testTag("update-ignore"),
                                )
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            if (state.loading || state.busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("update-working"))
            // Instantiate the saveable scroll only once the disk-backed document is available.
            // An empty loading layout would otherwise clamp the restored offset to zero.
            val logModifier = Modifier.weight(1f).fillMaxWidth()
            if (state.request == null)
                Column(logModifier.padding(12.dp)) {
                    state.error?.let {
                        Text(
                            it,
                            Modifier.testTag("update-error"),
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (!state.loading)
                            TextButton(
                                onClick = retry,
                                modifier = Modifier.testTag("update-retry"),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                    }
                }
            else
                Column(
                    logModifier
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                        .testTag("update-log")
                ) {
                    state.error?.let {
                        Text(
                            it,
                            Modifier.testTag("update-error"),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    SearchableRichText(state.document, emptyList(), -1, images, link, image, {})
                }
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                TextButton(
                    onClick = cancel,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).testTag("update-cancel"),
                ) {
                    Text(stringResource(R.string.text_return))
                }
                TextButton(
                    onClick = { download(UpdateDownloadTarget.Primary) },
                    enabled = state.canAct,
                    modifier = Modifier.weight(1f).testTag("update-now"),
                ) {
                    Text(stringResource(R.string.beta_update_now))
                }
            }
        }
    }
}
