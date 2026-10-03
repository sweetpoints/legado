package io.legado.app.ui.browser

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.constant.SourceType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.browser.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal data class BrowserPageDelivery(
    val page: BrowserPage,
    val source: BaseSource?,
    val cookies: BrowserWebCookies?,
)

internal data class BrowserHostActions(
    val ready: () -> Boolean,
    val installed: () -> Boolean,
    val install: (BrowserPageDelivery) -> Unit,
    val capture: (String) -> Unit,
    val receipt: (BrowserReceipt) -> Unit,
    val folder: (String?) -> Unit,
    val back: () -> Unit,
    val close: () -> Unit,
    val menu: (BrowserMenu) -> Unit,
    val fullscreen: (Boolean) -> Unit,
)

@Composable
internal fun BrowserRoute(
    model: BrowserViewModel,
    host: BrowserHostActions,
    web: View,
    videoView: View?,
    documentReady: Boolean,
    log: Boolean,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val callbacks by rememberUpdatedState(host)
    var closed by remember { mutableStateOf(false) }
    var preparationError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val close = {
        if (!closed) {
            closed = true
            callbacks.close()
        }
    }
    LaunchedEffect(model, lifecycle, documentReady, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var capturing: String? = null
            model.state.collect { value ->
                if (
                    value.loading ||
                        value.loadFailed ||
                        preparationError != null ||
                        !callbacks.ready()
                )
                    return@collect
                try {
                    val receipt = value.receipt
                    if (receipt != null) {
                        val prepared = model.prepareReceipt(receipt)
                        if (
                            callbacks.ready() &&
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                model.consumeReceipt(receipt)
                        ) {
                            callbacks.receipt(prepared)
                            if (value.finished) close()
                        }
                        return@collect
                    }
                    if (value.finished) {
                        close()
                        return@collect
                    }
                    value.page?.let { page ->
                        if (!callbacks.installed()) {
                            val cookie = model.webCookies(page.baseUrl)
                            val source =
                                withContext(Dispatchers.Default) {
                                    page.source?.let {
                                        when (it.type) {
                                            SourceType.book ->
                                                GSON.fromJson(it.json, BookSource::class.java)
                                            SourceType.rss ->
                                                GSON.fromJson(it.json, RssSource::class.java)
                                            else -> null
                                        }
                                    }
                                }
                            currentCoroutineContext().ensureActive()
                            if (
                                callbacks.ready() &&
                                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                    !callbacks.installed()
                            )
                                callbacks.install(BrowserPageDelivery(page, source, cookie))
                        }
                    }
                    val image = value.imageRequest
                    if (image != null) {
                        val directory = model.imageDirectory()
                        currentCoroutineContext().ensureActive()
                        if (
                            callbacks.ready() &&
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                model.consumeImageRequest(image)
                        ) {
                            if (value.selectImageDirectory || directory.isNullOrEmpty())
                                callbacks.folder(directory)
                            else model.saveImage(directory)
                        }
                    }
                    val capture = value.capture
                    if (
                        documentReady &&
                            capture != null &&
                            capture != capturing &&
                            callbacks.ready()
                    ) {
                        capturing = capture
                        callbacks.capture(capture)
                    }
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    preparationError = error.localizedMessage.orEmpty()
                }
            }
        }
    }
    LaunchedEffect(state.fullscreen) { callbacks.fullscreen(state.fullscreen) }
    BackHandler(onBack = host.back)
    BrowserScreen(
        state.copy(
            error = preparationError ?: state.error,
            persistError = preparationError != null || state.persistError,
        ),
        BrowserScreenActions(
            host.back,
            { action ->
                when (action) {
                    BrowserMenu.Confirm -> model.verify()
                    BrowserMenu.Fullscreen -> model.fullscreen(!state.fullscreen)
                    BrowserMenu.Disable -> model.disableSource()
                    BrowserMenu.Delete -> model.deleteSource()
                    else -> callbacks.menu(action)
                }
            },
            {
                if (preparationError != null) {
                    preparationError = null
                    retry++
                } else model.retry()
            },
            model::dismissImageActions,
            { model.requestImageDirectory(false) },
            { model.requestImageDirectory(true) },
        ),
        modifier,
        log,
        videoView != null,
        snackbar,
        webContent = { AndroidView(factory = { web }, modifier = Modifier.fillMaxSize()) },
        videoContent = {
            videoView?.let { nativeView ->
                AndroidView(factory = { nativeView }, modifier = Modifier.fillMaxSize())
            }
        },
    )
}
