package io.legado.app.ui.browser

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.legado.app.R

internal enum class BrowserMenu(val label: Int) {
    Refresh(R.string.refresh), Confirm(R.string.ok), Open(R.string.open_in_browser), Copy(R.string.copy_url),
    Fullscreen(R.string.full_screen), Log(R.string.show_web_log), Disable(R.string.disable_source), Delete(R.string.delete_source)
}
internal val browserMenus = BrowserMenu.entries.toList()
internal data class BrowserScreenActions(val back: () -> Unit, val menu: (BrowserMenu) -> Unit,
    val retry: () -> Unit, val imageDismiss: () -> Unit, val saveImage: () -> Unit, val selectImageFolder: () -> Unit)

@Composable internal fun BrowserScreen(state: BrowserState, actions: BrowserScreenActions, modifier: Modifier = Modifier,
    log: Boolean = false, video: Boolean = false, snackbar: SnackbarHostState = remember { SnackbarHostState() },
    insets: WindowInsets = WindowInsets.systemBars, webContent: @Composable () -> Unit, videoContent: @Composable () -> Unit = {}) {
    var menu by rememberSaveable { mutableStateOf(false) }; var delete by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.loading && !state.loadFailed && !state.busy && !state.finished && !state.persistError
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().then(if (video) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier)
                .windowInsetsPadding(insets).imePadding().testTag("browser-page")) {
                if (!state.fullscreen) Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(actions.back, Modifier.size(48.dp).testTag("browser-back")) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) }
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(state.title.ifEmpty { if (state.progress == 0) stringResource(R.string.loading) else "" }, maxLines = 2, modifier = Modifier.testTag("browser-title"))
                            state.page?.request?.sourceName?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.testTag("browser-subtitle")) }
                        }
                        IconButton({ actions.menu(BrowserMenu.Refresh) }, Modifier.size(48.dp).testTag("browser-refresh"), enabled = enabled) {
                            Icon(painterResource(R.drawable.ic_refresh_black_24dp), stringResource(R.string.refresh))
                        }
                        IconButton({ actions.menu(BrowserMenu.Confirm) }, Modifier.size(48.dp).testTag("browser-confirm"), enabled = enabled) {
                            Icon(painterResource(R.drawable.ic_check), stringResource(R.string.ok))
                        }
                        Box {
                            IconButton({ menu = true }, Modifier.size(48.dp).testTag("browser-menu")) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu)) }
                            DropdownMenu(menu, { menu = false }) {
                                browserMenus.drop(2).filter { it != BrowserMenu.Disable && it != BrowserMenu.Delete || state.page?.request?.sourceOrigin?.isNotEmpty() == true }.forEach { item ->
                                    DropdownMenuItem(text = { Text(stringResource(item.label)) }, enabled = enabled,
                                        leadingIcon = if (item == BrowserMenu.Log) ({ Checkbox(log, null) }) else null,
                                        modifier = Modifier.testTag("browser-menu-${item.name}"), onClick = { menu = false; if (item == BrowserMenu.Delete) delete = true else actions.menu(item) })
                                }
                            }
                        }
                    }
                }
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(1.dp).testTag("browser-progress"))
                else if (state.progress in 1..99) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(1.dp).testTag("browser-progress"))
                state.error?.let { message ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f).testTag("browser-error"), color = MaterialTheme.colorScheme.error)
                        if (state.loadFailed || state.persistError) TextButton(actions.retry, Modifier.heightIn(min = 48.dp).testTag("browser-retry")) { Text(stringResource(R.string.retry)) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().testTag("browser-web-core")) { webContent() }
            }
            if (video) Box(Modifier.fillMaxSize().testTag("browser-video-core")) { videoContent() }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(stringResource(R.string.draw)) },
        text = { Text(stringResource(R.string.sure_del) + "\n" + state.page?.request?.sourceName.orEmpty()) },
        confirmButton = { TextButton({ delete = false; actions.menu(BrowserMenu.Delete) }, Modifier.heightIn(min = 48.dp).testTag("browser-delete-confirm"), enabled = enabled) { Text(stringResource(R.string.yes)) } },
        dismissButton = { TextButton({ delete = false }, Modifier.heightIn(min = 48.dp).testTag("browser-delete-cancel")) { Text(stringResource(R.string.no)) } })
    if (state.imageActions) AlertDialog(onDismissRequest = actions.imageDismiss, text = {
        Column { TextButton(actions.saveImage, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("browser-image-save")) { Text(stringResource(R.string.action_save)) }
            TextButton(actions.selectImageFolder, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("browser-image-folder")) { Text(stringResource(R.string.select_folder)) } }
    }, confirmButton = {})
}
