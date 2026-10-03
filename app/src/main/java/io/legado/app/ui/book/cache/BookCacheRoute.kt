package io.legado.app.ui.book.cache

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

@Composable
fun BookCacheRoute(
    model: BookCacheViewModel,
    onClose: () -> Unit,
    onFolder: (BookCacheFolder) -> Unit,
    onLog: () -> Unit,
    onError: (String) -> Unit,
    canHandle: () -> Boolean = { true },
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    val folder by rememberUpdatedState(onFolder)
    val log by rememberUpdatedState(onLog)
    val error by rememberUpdatedState(onError)
    val ready by rememberUpdatedState(canHandle)
    var closed by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.refresh()
            model.state.collect { value ->
                if (!ready()) return@collect
                if (value.closed && !closed) {
                    closed = true
                    close()
                } else
                    value.folder
                        ?.takeUnless { it.delivered }
                        ?.let { request ->
                            if (model.consumeFolder(request.ticket))
                                try {
                                    folder(request)
                                } catch (failure: Exception) {
                                    model.abandonFolder()
                                    error(failure.localizedMessage ?: "ERROR")
                                }
                        }
            }
        }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { runCatching { model.flushSection() } }
            }
        }
    }
    val actions =
        remember(model, owner) {
            BookCacheActions(
                model::close,
                model::group,
                model::download,
                model::confirmDownload,
                model::cancelDownloadConfirmation,
                model::toggleDownload,
                { model.export(it) },
                { model.export(folderOnly = true) },
                model::preferences,
                model::refreshPreferences,
                model::section,
                model::confirmSection,
                model::cancelSection,
                model::previewEpisodeName,
                model::rememberEpisodeName,
                model::openSettings,
                model::settings,
                model::confirmSettings,
                model::cancelSettings,
                {
                    if (
                        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()
                    ) {
                        try {
                            log()
                        } catch (failure: Exception) {
                            error(failure.localizedMessage ?: "ERROR")
                        }
                    }
                },
                model::retry,
                model::abandonFolder,
            )
        }
    BackHandler { model.close() }
    BookCacheScreen(state, actions)
}
