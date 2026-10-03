package io.legado.app.ui.rss.read

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.legado.app.R

class RssReaderActions(
    val back: () -> Unit,
    val menu: (Boolean) -> Unit,
    val action: (RssReaderAction) -> Unit,
    val retry: () -> Unit,
)

/** Chrome is Compose; only the browser and its custom video view occupy platform slots. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssReaderScreen(
    state: RssReaderState,
    fullscreen: Boolean,
    actions: RssReaderActions,
    snackbar: @Composable () -> Unit = {},
    browser: @Composable () -> Unit,
    customVideo: @Composable () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            // Keep the browser composed while video is fullscreen, retaining its document and pool
            // lease.
            Column(
                Modifier.fillMaxSize()
                    .alpha(if (fullscreen) 0f else 1f)
                    .then(if (fullscreen) Modifier.clearAndSetSemantics {} else Modifier)
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
            ) {
                TopAppBar(
                    title = { Text(state.title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(actions.back, modifier = Modifier.testTag("rss-reader-back")) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_back),
                                stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            { actions.action(RssReaderAction.Refresh) },
                            enabled = state.loaded && !state.busy,
                            modifier = Modifier.testTag("rss-reader-refresh"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_refresh_black_24dp),
                                stringResource(R.string.refresh),
                            )
                        }
                        if (state.article)
                            IconButton(
                                { actions.action(RssReaderAction.Favorite) },
                                enabled = !state.busy,
                                modifier = Modifier.testTag("rss-reader-favorite"),
                            ) {
                                Icon(
                                    painterResource(
                                        if (state.favorite) R.drawable.ic_star
                                        else R.drawable.ic_star_border
                                    ),
                                    stringResource(
                                        if (state.favorite) R.string.in_favorites
                                        else R.string.out_favorites
                                    ),
                                )
                            }
                        IconButton(
                            { actions.action(RssReaderAction.Share) },
                            enabled = state.loaded && !state.busy,
                            modifier = Modifier.testTag("rss-reader-share"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_share),
                                stringResource(R.string.share),
                            )
                        }
                        Box {
                            IconButton(
                                { actions.menu(true) },
                                enabled = state.loaded && !state.busy,
                                modifier = Modifier.testTag("rss-reader-more"),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_more_vert),
                                    stringResource(R.string.more_menu),
                                )
                            }
                            DropdownMenu(state.menu, { actions.menu(false) }) {
                                ReaderMenu(
                                    if (state.speaking) R.string.aloud_stop
                                    else R.string.read_aloud,
                                    "speech",
                                ) {
                                    actions.action(RssReaderAction.Speech)
                                }
                                if (state.canLogin)
                                    ReaderMenu(R.string.login, "login") {
                                        actions.action(RssReaderAction.Login)
                                    }
                                ReaderMenu(R.string.open_in_browser, "browser") {
                                    actions.action(RssReaderAction.Browser)
                                }
                                ReaderMenu(R.string.read_record, "records") {
                                    actions.action(RssReaderAction.ReadRecords)
                                }
                                ReaderMenu(R.string.edit_source, "edit") {
                                    actions.action(RssReaderAction.EditSource)
                                }
                                ReaderMenu(R.string.log, "log") {
                                    actions.action(RssReaderAction.Log)
                                }
                            }
                        }
                    },
                )
                if (state.progress in 1..99)
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier =
                            Modifier.fillMaxWidth().height(1.dp).testTag("rss-reader-progress"),
                    )
                if (state.error != null)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(
                            state.error,
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(actions.retry, modifier = Modifier.testTag("rss-reader-retry")) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                Box(Modifier.weight(1f).fillMaxWidth().testTag("rss-reader-browser")) { browser() }
            }
            if (fullscreen)
                Box(Modifier.fillMaxSize().testTag("rss-reader-fullscreen")) { customVideo() }
            snackbar()
        }
    }
}

@Composable
private fun ReaderMenu(label: Int, tag: String, click: () -> Unit) {
    // The legacy aloud entry intentionally has no overflow icon.
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = click,
        modifier = Modifier.testTag("rss-reader-$tag"),
    )
}
