package io.legado.app.ui.book.read

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.ui.components.image.LifecycleDrawablePainter
import io.legado.app.ui.widget.dialog.photo.PhotoImage
import io.legado.app.ui.widget.dialog.photo.PhotoImageLoader
import io.legado.app.ui.widget.dialog.photo.PhotoRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive

/** Runtime aspect cache contains only dimensions; no bitmap or Drawable enters SavedState. */
internal class ReviewImageDimensions {
    private val ratios = mutableStateMapOf<String, Float>()

    fun ratio(src: String): Float? = ratios[src]

    fun update(src: String, width: Float, height: Float) {
        if (width > 0 && height > 0) ratios[src] = width / height
    }
}

@Composable
internal fun ReviewDetailImage(
    src: String,
    sourceKey: String,
    loader: PhotoImageLoader,
    dimensions: ReviewImageDimensions,
    modifier: Modifier = Modifier,
    media: Boolean = false,
    badge: Boolean = false,
    description: String? = null,
) {
    var image by remember(src, sourceKey, loader) { mutableStateOf<PhotoImage?>(null) }
    LaunchedEffect(src, sourceKey, loader) {
        var animated: PhotoImage.Animated? = null
        try {
            val loaded = loader.load(PhotoRequest(src, sourceOrigin = sourceKey))
            animated = loaded as? PhotoImage.Animated
            coroutineContext.ensureActive()
            when (loaded) {
                is PhotoImage.Static ->
                    dimensions.update(
                        src,
                        loaded.bitmap.width.toFloat(),
                        loaded.bitmap.height.toFloat(),
                    )
                is PhotoImage.Animated ->
                    dimensions.update(
                        src,
                        loaded.drawable.intrinsicWidth.toFloat(),
                        loaded.drawable.intrinsicHeight.toFloat(),
                    )
            }
            image = loaded
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            coroutineContext.ensureActive()
        } finally {
            animated?.release()
        }
    }
    val painter =
        when (val value = image) {
            is PhotoImage.Static -> remember(value) { BitmapPainter(value.bitmap.asImageBitmap()) }
            is PhotoImage.Animated -> remember(value) { LifecycleDrawablePainter(value) }
            null -> null
        }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(painter, lifecycle) {
        val animation = painter as? LifecycleDrawablePainter
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> animation?.start()
                Lifecycle.Event.ON_STOP -> animation?.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) animation?.start()
        onDispose {
            lifecycle.removeObserver(observer)
            animation?.stop()
        }
    }
    val ratio = dimensions.ratio(src) ?: 1f
    val size =
        when {
            media -> Modifier.fillMaxWidth().heightIn(max = 240.dp).aspectRatio(ratio)
            badge -> Modifier.height(20.dp).width((20 * ratio).dp.coerceAtMost(200.dp))
            else -> Modifier
        }
    if (painter != null)
        Image(
            painter,
            description,
            modifier.then(size),
            alignment = Alignment.TopStart,
            contentScale = ContentScale.Fit,
        )
    else Box(modifier.then(size).background(MaterialTheme.colorScheme.surfaceVariant))
}
