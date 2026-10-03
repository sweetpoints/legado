package io.legado.app.ui.components.cover

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.image.CoverImage
import io.legado.app.data.image.CoverLoadResult
import io.legado.app.data.repository.CoverRepository
import io.legado.app.data.repository.CoverRequest
import io.legado.app.data.repository.GlideCoverRepository
import io.legado.app.ui.components.image.LifecycleDrawablePainter
import kotlinx.coroutines.*

/** 3:4, center-cropped cover respecting caller constraints and the original 12-pixel corners. */
@Composable
fun ComposeCover(
    request: CoverRequest,
    modifier: Modifier = Modifier,
    contentDescription: String? = request.name,
    onLoadFinish: (() -> Unit)? = null,
    repository: CoverRepository? = null,
) {
    val context = LocalContext.current.applicationContext
    val activeRepository =
        remember(context, repository) { repository ?: GlideCoverRepository.get(context) }
    val configuration by activeRepository.configurations.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val finished by rememberUpdatedState(onLoadFinish)
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    var image by remember(request, activeRepository) { mutableStateOf<CoverImage?>(null) }
    var title by remember(request, activeRepository) { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(activeRepository, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) activeRepository.refreshConfiguration()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(request, activeRepository, configuration, dimensions) {
        val config = configuration ?: return@LaunchedEffect
        if (dimensions.width <= 0 || dimensions.height <= 0) return@LaunchedEffect
        image = CoverImage.Static(config.defaultBitmap)
        title = null
        var animation: CoverImage.Animated? = null
        suspend fun generatedTitle(): Bitmap? =
            try {
                activeRepository.title(request, config, dimensions.width, dimensions.height)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        try {
            coroutineScope {
                // Preserve the old title fallback after 1200ms when a remote cover is still
                // pending.
                val titleJob = launch {
                    if (!config.useDefault && request.normalizedPath != null) delay(1200)
                    title = generatedTitle()
                }
                val result =
                    try {
                        activeRepository.load(request, config, dimensions.width, dimensions.height)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        CoverLoadResult(CoverImage.Static(config.defaultBitmap), true)
                    }
                animation = result.image as? CoverImage.Animated
                ensureActive()
                image = result.image
                titleJob.cancelAndJoin()
                title = if (result.needsTitle) generatedTitle() else null
                // The previous forced-default path has no network completion callback.
                if (!config.useDefault) finished?.invoke()
                awaitCancellation()
            }
        } finally {
            animation?.resource?.release()
        }
    }
    val radius = with(LocalDensity.current) { 12f.toDp() }
    Box(
        modifier.aspectRatio(3f / 4f).clip(RoundedCornerShape(radius)).onSizeChanged {
            dimensions = it
        }
    ) {
        val current = image
        val painter =
            if (current == null) painterResource(R.drawable.image_cover_default)
            else rememberCoverPainter(current)
        Image(painter, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        title?.let { bitmap ->
            Image(
                remember(bitmap) { BitmapPainter(bitmap.asImageBitmap()) },
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

@Composable
private fun rememberCoverPainter(image: CoverImage): Painter =
    when (image) {
        is CoverImage.Static ->
            remember(image.bitmap) { BitmapPainter(image.bitmap.asImageBitmap()) }
        is CoverImage.Animated -> {
            val resource = image.resource
            val painter = remember(resource) { LifecycleDrawablePainter(resource) }
            val owner = LocalLifecycleOwner.current
            DisposableEffect(resource, owner) {
                val observer = LifecycleEventObserver { _, _ ->
                    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        painter.start()
                    else painter.stop()
                }
                owner.lifecycle.addObserver(observer)
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) painter.start()
                onDispose {
                    owner.lifecycle.removeObserver(observer)
                    painter.stop()
                }
            }
            painter
        }
    }
