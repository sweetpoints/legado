package io.legado.app.ui.book.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.image.MangaImageRepository
import io.legado.app.data.image.MangaImageRequest
import io.legado.app.data.preferences.MangaColorFilterValues
import io.legado.app.ui.components.image.LifecycleDrawablePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

internal data class MangaImageUiState(
    val resource: AnimatedDrawableResource? = null,
    val isLoading: Boolean = true,
    val progress: Int = 0,
    val error: String? = null,
)

@Composable
internal fun MangaImageRoute(
    request: MangaImageRequest,
    repository: MangaImageRepository,
    horizontal: Boolean,
    isLastImage: Boolean,
    viewportWidth: Dp,
    viewportHeight: Dp,
    colorFilter: MangaColorFilterValues,
    isEInk: Boolean,
    modifier: Modifier = Modifier,
) {
    var attempt by remember(request) { mutableIntStateOf(0) }
    var state by remember(request, attempt) { mutableStateOf(MangaImageUiState()) }
    LaunchedEffect(request, repository, attempt) {
        var resource: AnimatedDrawableResource? = null
        try {
            resource =
                repository.load(request) { progress ->
                    if (coroutineContext.isActive) state = state.copy(progress = progress)
                }
            coroutineContext.ensureActive()
            state = MangaImageUiState(resource = resource, isLoading = false)
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            coroutineContext.ensureActive()
            state =
                MangaImageUiState(
                    isLoading = false,
                    error = error.localizedMessage ?: error.toString(),
                )
        } finally {
            // Glide owns static pixels and animation frames: release only after drawing has
            // stopped.
            resource?.release()
        }
    }
    MangaImageScreen(
        state = state,
        horizontal = horizontal,
        isLastImage = isLastImage,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        colorFilter = colorFilter,
        isEInk = isEInk,
        onRetry = { attempt++ },
        modifier = modifier,
    )
}

@Composable
internal fun MangaImageScreen(
    state: MangaImageUiState,
    horizontal: Boolean,
    isLastImage: Boolean,
    viewportWidth: Dp,
    viewportHeight: Dp,
    colorFilter: MangaColorFilterValues,
    isEInk: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val painter = remember(state.resource) { state.resource?.let(::LifecycleDrawablePainter) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(painter, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> painter?.start()
                Lifecycle.Event.ON_STOP -> painter?.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) painter?.start()
        onDispose {
            lifecycle.removeObserver(observer)
            painter?.stop()
        }
    }
    val intrinsic = painter?.intrinsicSize
    val imageHeight =
        if (intrinsic != null && intrinsic.width > 0f) {
            viewportWidth * (intrinsic.height / intrinsic.width)
        } else {
            viewportHeight
        }
    val pageHeight =
        when {
            horizontal || state.isLoading || state.error != null -> viewportHeight
            isLastImage -> imageHeight.coerceAtLeast(viewportHeight * (2f / 3f))
            else -> imageHeight
        }
    Box(
        modifier = modifier.fillMaxWidth().height(pageHeight).testTag("manga-image-page"),
        contentAlignment = if (horizontal) Alignment.Center else Alignment.TopCenter,
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                colorFilter = mangaImageColorFilter(colorFilter),
                contentScale = if (horizontal) ContentScale.Fit else ContentScale.FillWidth,
                modifier =
                    if (horizontal) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().height(imageHeight),
            )
        }
        if (state.isLoading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!isEInk) CircularProgressIndicator()
                Text(text = "${state.progress}%", modifier = Modifier.padding(8.dp))
            }
        }
        if (state.error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

@Composable
private fun mangaImageColorFilter(values: MangaColorFilterValues): ColorFilter =
    remember(values) {
        val bounded = values.bounded()
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    (255 - bounded.red) / 255f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    (255 - bounded.green) / 255f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    (255 - bounded.blue) / 255f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    (255 - bounded.alpha) / 255f,
                    0f,
                )
            )
        )
    }
