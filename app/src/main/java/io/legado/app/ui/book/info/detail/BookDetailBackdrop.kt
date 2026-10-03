package io.legado.app.ui.book.info.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.image.CoverImage
import io.legado.app.data.repository.BookDetailBackdropRepository
import io.legado.app.data.repository.BookDetailBook
import io.legado.app.data.repository.GlideBookDetailBackdropRepository
import io.legado.app.ui.components.image.LifecycleDrawablePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive

@Composable
fun BookDetailBackdrop(
    book: BookDetailBook,
    modifier: Modifier = Modifier,
    repository: BookDetailBackdropRepository? = null,
) {
    val context = LocalContext.current.applicationContext
    val loader =
        remember(context, repository) { repository ?: GlideBookDetailBackdropRepository(context) }
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    var image by remember(book.cover, loader) { mutableStateOf<CoverImage?>(null) }
    LaunchedEffect(book.cover, loader, dimensions) {
        if (dimensions.width <= 0 || dimensions.height <= 0) return@LaunchedEffect
        var animation: CoverImage.Animated? = null
        try {
            val result = loader.load(book.cover, dimensions.width, dimensions.height)
            animation = result as? CoverImage.Animated
            ensureActive()
            image = result
            awaitCancellation()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            image = null
        } finally {
            animation?.resource?.release()
        }
    }
    Box(modifier.onSizeChanged { dimensions = it }) {
        val current = image
        if (current != null)
            Image(
                backdropPainter(current),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
    }
}

@Composable
private fun backdropPainter(image: CoverImage): Painter =
    when (image) {
        is CoverImage.Static ->
            remember(image.bitmap) { BitmapPainter(image.bitmap.asImageBitmap()) }
        is CoverImage.Animated -> {
            val resource = image.resource
            val painter = remember(resource) { LifecycleDrawablePainter(resource) }
            val owner = LocalLifecycleOwner.current
            DisposableEffect(resource, owner) {
                val update = {
                    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        painter.start()
                    else painter.stop()
                }
                val observer = LifecycleEventObserver { _, _ -> update() }
                owner.lifecycle.addObserver(observer)
                update()
                onDispose {
                    owner.lifecycle.removeObserver(observer)
                    painter.stop()
                }
            }
            painter
        }
    }
