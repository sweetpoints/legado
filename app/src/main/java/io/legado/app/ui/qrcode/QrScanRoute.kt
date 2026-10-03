package io.legado.app.ui.qrcode

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun QrScanRoute(
    model: QrScanViewModel,
    cameraAvailable: Boolean,
    ready: () -> Boolean,
    onGallery: () -> Unit,
    onResult: (String?) -> Unit,
    onBack: () -> Unit,
    preview: @Composable (Boolean) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val currentReady by rememberUpdatedState(ready)
    val currentResult by rememberUpdatedState(onResult)
    val currentGallery by rememberUpdatedState(onGallery)
    val currentBack by rememberUpdatedState(onBack)
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state
                .map { it.pendingResult to it.error }
                .distinctUntilChanged()
                .collect { (pending, failure) ->
                    if (failure != null) return@collect
                    pending?.let { revision ->
                        try {
                            val result =
                                model.consume(revision) {
                                    currentReady() &&
                                        owner.lifecycle.currentState == Lifecycle.State.RESUMED
                                }
                            currentCoroutineContext().ensureActive()
                            if (
                                result != null &&
                                    currentReady() &&
                                    owner.lifecycle.currentState == Lifecycle.State.RESUMED
                            )
                                currentResult(result.text)
                        } catch (canceled: kotlinx.coroutines.CancellationException) {
                            throw canceled
                        } catch (error: Exception) {
                            currentCoroutineContext().ensureActive()
                            model.failed(error.localizedMessage ?: "ERROR")
                        }
                    }
                }
        }
    }
    fun available() = currentReady() && owner.lifecycle.currentState == Lifecycle.State.RESUMED
    BackHandler { if (available()) currentBack() }
    QrScanScreen(
        state,
        cameraAvailable,
        { if (available()) currentGallery() },
        { if (available()) currentBack() },
        model::retry,
        preview,
    )
}
