package io.legado.app.ui.association

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException

@Composable
internal fun VerificationCodeRoute(
    viewModel: VerificationCodeViewModel,
    onClose: () -> Unit,
    onShowImage: (String) -> Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current.applicationContext
    val loader = remember(context) { VerificationCodeImageLoader(context) }
    val close by rememberUpdatedState(onClose)
    val showImage by rememberUpdatedState(onShowImage)
    var retry by rememberSaveable { mutableIntStateOf(0) }
    var image by remember(viewModel, retry) { mutableStateOf(VerificationImageState()) }
    var pendingImageSrc by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel, retry, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (image.bitmap == null) {
                image = VerificationImageState()
                try {
                    val loaded = loader.load(viewModel.imageUrl, viewModel.sourceOrigin)
                    image =
                        VerificationImageState(
                            bitmap = loaded.bitmap,
                            previewSrc = loaded.previewSrc,
                            loading = false,
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    image =
                        VerificationImageState(
                            loading = false,
                            error = error.localizedMessage ?: error.toString(),
                        )
                }
            }
        }
    }
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { if (it.closeRequested) close() }
        }
    }
    LaunchedEffect(pendingImageSrc, lifecycle) {
        pendingImageSrc?.let { src ->
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (pendingImageSrc == src && showImage(src)) pendingImageSrc = null
            }
        }
    }
    VerificationCodeScreen(
        state = state,
        image = image,
        onCodeChanged = viewModel::updateCode,
        onSubmit = viewModel::submit,
        onDisable = viewModel::disableSource,
        onRequestDelete = viewModel::requestDelete,
        onConfirmDelete = viewModel::deleteSource,
        onCancelDelete = viewModel::cancelDelete,
        onShowImage = { pendingImageSrc = image.previewSrc },
        onRetryImage = { retry++ },
        onClose = close,
    )
}
