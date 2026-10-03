package io.legado.app.ui.book.read

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

@Composable
internal fun ContentEditorRoute(
    model: ContentEditorViewModel,
    onCopy: (String) -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val reload by rememberUpdatedState(onReload)
    val close by rememberUpdatedState(onClose)
    BackHandler { model.close() }
    LaunchedEffect(model, owner) {
        var closed = false
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect {
                if (model.consumeReload()) reload()
                if (it.finished && !closed) {
                    closed = true
                    close()
                }
            }
        }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { model.flushDraft() }
            }
        }
    }
    ContentEditorScreen(
        state,
        ContentEditorActions(
            { model.close() },
            model::save,
            model::reset,
            { onCopy(model.copyText()) },
            model::togglePlainText,
            model::setSearchVisible,
            model::setQuery,
            model::setRegex,
            model::setMatchCase,
            model::nextMatch,
            model::edit,
            model::setScroll,
            model::openTitle,
            model::editTitle,
            model::dismissTitle,
            model::saveTitle,
            model::retryLoad,
        ),
        model.target.chapterPos,
        modifier,
    )
}
