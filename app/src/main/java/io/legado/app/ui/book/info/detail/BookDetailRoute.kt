package io.legado.app.ui.book.info.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.data.repository.BookDetailBook
import io.legado.app.data.repository.BookDetailChildKind
import io.legado.app.data.repository.BookDetailChildOwner
import io.legado.app.data.repository.BookDetailIntroImageRepository
import io.legado.app.data.repository.BookDetailNativeKind
import io.legado.app.data.repository.BookDetailNativePayload
import io.legado.app.data.repository.BookDetailNativeRepository
import io.legado.app.data.repository.BookDetailNativeTexts
import io.legado.app.data.repository.BookDetailPreferences
import io.legado.app.data.repository.CoverRequest
import io.legado.app.data.repository.materializeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private fun BookDetailNativePayload.child(): BookDetailChildOwner? {
    val kind =
        when (effect.kind) {
            BookDetailNativeKind.Toc -> BookDetailChildKind.Toc
            BookDetailNativeKind.Reader -> BookDetailChildKind.Reader
            BookDetailNativeKind.EditInfo -> BookDetailChildKind.InfoEditor
            BookDetailNativeKind.EditSource -> BookDetailChildKind.SourceEditor
            BookDetailNativeKind.ChooseFolder -> BookDetailChildKind.Folder
            BookDetailNativeKind.ChangeCover -> BookDetailChildKind.Cover
            BookDetailNativeKind.Group -> BookDetailChildKind.Group
            BookDetailNativeKind.SourceVariable,
            BookDetailNativeKind.BookVariable -> BookDetailChildKind.Variable
            BookDetailNativeKind.ChangeSource -> BookDetailChildKind.Source
            else -> return null
        }
    return BookDetailChildOwner(
        effect.token,
        kind,
        book?.bookUrl.orEmpty(),
        if (effect.kind == BookDetailNativeKind.BookVariable) null else source?.bookSourceUrl,
    )
}

/**
 * UI collection follows lifecycle; requests and durable child receipts continue in the independent
 * VM.
 */
@Composable
fun BookDetailRoute(
    model: BookDetailViewModel,
    preferences: BookDetailPreferences,
    native: BookDetailNativeRepository,
    texts: BookDetailNativeTexts,
    onClose: () -> Unit,
    onNative: (BookDetailNativePayload) -> Unit,
    onAction: (BookDetailAction) -> Unit,
    onClick: (BookDetailClick, String?, Boolean) -> Unit,
    onIntroAction: (io.legado.app.ui.dict.DictionaryResultAction) -> Unit,
    onIntroLink: (String) -> Unit,
    onIntroImage: (String) -> Unit,
    onError: (String) -> Unit,
    canHandle: () -> Boolean = { true },
    backdrop: @Composable (BookDetailBook, Modifier) -> Unit = { book, layout ->
        BookDetailBackdrop(book, layout)
    },
    cover: @Composable (CoverRequest, Modifier) -> Unit = { request, layout ->
        io.legado.app.ui.components.cover.ComposeCover(request, layout)
    },
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    val deliver by rememberUpdatedState(onNative)
    val failure by rememberUpdatedState(onError)
    val ready by rememberUpdatedState(canHandle)
    val preparation by rememberUpdatedState(native)
    val preparationTexts by rememberUpdatedState(texts)
    var closeDelivered by remember(model) { mutableStateOf(false) }
    var routeError by remember(model) { mutableStateOf<String?>(null) }
    var failedToken by remember(model) { mutableStateOf<String?>(null) }
    var retryEpoch by remember(model) { mutableIntStateOf(0) }
    val context = LocalContext.current.applicationContext
    val images = remember(context) { BookDetailIntroImageRepository(context) }
    LaunchedEffect(model, lifecycle, retryEpoch) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { current ->
                if (!ready()) return@collect
                if (current.closed) {
                    if (!closeDelivered) {
                        closeDelivered = true
                        close()
                    }
                    return@collect
                }
                if (
                    !current.loaded ||
                        current.busy ||
                        current.childPending ||
                        current.session?.pendingService != null
                )
                    return@collect
                val effect = current.session?.effects?.firstOrNull() ?: return@collect
                if (failedToken == effect.token) return@collect
                val available = {
                    lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        ready() &&
                        !model.state.value.closed
                }
                try {
                    val payload = preparation.prepare(effect, preparationTexts)
                    currentCoroutineContext().ensureActive()
                    if (!available()) return@collect
                    payload.child()?.let { model.registerChild(it) }
                    currentCoroutineContext().ensureActive()
                    if (!available()) return@collect
                    if (model.consumeEffect(effect.token, available) != null) deliver(payload)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (!available()) return@collect
                    val message = error.localizedMessage ?: error.toString()
                    // A failed native preparation/launch is explicitly retried from its original
                    // page action.
                    // A disk claim failure remains pending, with a visible retry instead of
                    // repeating on every state emission.
                    try {
                        model.consumeEffect(effect.token, available)
                        failure(message)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (claim: Exception) {
                        currentCoroutineContext().ensureActive()
                        failedToken = effect.token
                        routeError = claim.localizedMessage ?: message
                    }
                }
            }
        }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    try {
                        model.flush()
                    } catch (error: Exception) {
                        if (!model.state.value.closed)
                            routeError = error.localizedMessage ?: error.toString()
                    }
                }
            }
        }
    }
    BackHandler { model.close() }
    val actions =
        BookDetailActions(
            model::close,
            onAction,
            onClick,
            model::introExpanded,
            model::retry,
            model::reload,
        )
    Column(Modifier.fillMaxSize()) {
        routeError?.let { message ->
            Row(Modifier.fillMaxWidth().padding(12.dp).testTag("book-detail-route-error")) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton({
                    routeError = null
                    failedToken = null
                    retryEpoch++
                }) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
        BookDetailScreen(
            state,
            preferences,
            actions,
            Modifier.weight(1f),
            background = backdrop,
            cover = cover,
            intro = { book, expanded, change, layout ->
                val source = state.data?.source
                key(book.intro, source?.json, retryEpoch) {
                    val document by
                        produceState<BookDetailIntroDocument?>(null, book.intro) {
                            try {
                                value =
                                    withContext(Dispatchers.IO) {
                                        bookDetailIntroDocument(book.intro)
                                    }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                ensureActive()
                                routeError = error.localizedMessage ?: error.toString()
                            }
                        }
                    val preparedSource by
                        produceState<io.legado.app.data.entities.BookSource?>(null, source?.json) {
                            try {
                                value = withContext(Dispatchers.IO) { source?.materializeSource() }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                ensureActive()
                                routeError = error.localizedMessage ?: error.toString()
                            }
                        }
                    val current = document
                    if (current != null && (source == null || preparedSource != null))
                        BookDetailIntro(
                            current,
                            book.bookUrl,
                            preparedSource,
                            expanded,
                            change,
                            { images.image(it, source?.url) },
                            { action ->
                                val active = model.state.value.data
                                if (
                                    !model.state.value.closed &&
                                        active?.book?.bookUrl == book.bookUrl &&
                                        active.book.intro == book.intro &&
                                        active.source == source
                                )
                                    onIntroAction(action)
                            },
                            { link -> if (!model.state.value.closed) onIntroLink(link) },
                            { image -> if (!model.state.value.closed) onIntroImage(image) },
                            layout,
                        )
                }
            },
        )
    }
}
