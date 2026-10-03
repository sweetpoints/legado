package io.legado.app.ui.book.search

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.model.webBook.BookSearchReceipt
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun BookSearchRoute(
    model: BookSearchViewModel,
    available: () -> Boolean,
    prepare: suspend (BookSearchReceipt) -> PreparedBookSearchEffect,
    handle: (PreparedBookSearchEffect) -> Unit,
    abandon: suspend (PreparedBookSearchEffect) -> Unit,
    close: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(available)
    val prepareDestination by rememberUpdatedState(prepare)
    val host by rememberUpdatedState(handle)
    val cleanup by rememberUpdatedState(abandon)
    val finish by rememberUpdatedState(close)
    BackHandler { finish() }
    val delivery =
        remember(model, lifecycle) {
            BookSearchEffectDelivery(
                available = {
                    val value = model.state.value
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        ready() &&
                        value.ready &&
                        value.nativeError == null &&
                        value.persistError == null &&
                        value.durableRevision >= value.draft.revision
                },
                prepare = { receipt -> prepareDestination(receipt) },
                consume = model::consumeReceipt,
                handle = { prepared -> host(prepared) },
                abandon = { prepared -> cleanup(prepared) },
                failure = model::preparationFailed,
            )
        }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.resume()
            try {
                awaitCancellation()
            } finally {
                model.pause()
            }
        }
    }
    LaunchedEffect(model, lifecycle, delivery) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state
                .map { value ->
                    value.draft.effects.firstOrNull()?.takeIf {
                        value.ready &&
                            value.persistError == null &&
                            value.nativeError == null &&
                            value.durableRevision >= value.draft.revision
                    }
                }
                .distinctUntilChanged()
                .collectLatest { receipt ->
                    if (receipt != null) delivery.deliver(receipt)
                }
        }
    }
    val actions =
        remember(model, lifecycle) {
            BookSearchActions(
                close = { finish() },
                query = model::editQuery,
                focus = model::focusInput,
                submit = model::submit,
                stop = model::stopSearch,
                continueSearch = { manual ->
                    if (manual || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        model.continueSearch(manual)
                    }
                },
                history = model::historyClicked,
                deleteHistory = model::deleteHistory,
                clearHistoryPrompt = model::confirmClearHistory,
                clearHistory = model::clearHistory,
                result = model::openBook,
                suggestion = model::openSuggestion,
                precision = model::togglePrecision,
                readRecords = model::toggleReadRecords,
                openFilter = model::openFilter,
                filter = model::editFilter,
                dismissFilter = model::dismissFilter,
                confirmFilter = model::confirmFilter,
                sources = model::openSources,
                scope = model::openScope,
                group = model::selectGroup,
                allSources = { model.selectScope("") },
                openedMenu = model::validateScopeMenu,
                log = model::openLog,
                retry = model::retry,
                dismissEmpty = model::dismissEmptyScope,
                confirmEmpty = model::confirmEmptyScope,
            )
        }
    BookSearchScreen(state, actions)
}
