package io.legado.app.ui.main.rss

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.MainRssRow
import io.legado.app.data.repository.RssArticleImageRepository
import io.legado.app.ui.components.image.LifecycleDrawablePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

/** Shared source-authenticated Glide lease, with the homepage's square crop and RSS fallback. */
@Composable
internal fun MainRssIcon(
    row: MainRssRow,
    repository: RssArticleImageRepository,
    modifier: Modifier = Modifier,
) {
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    var resource by
        remember(row.icon, row.sourceUrl, repository) {
            mutableStateOf<AnimatedDrawableResource?>(null)
        }
    LaunchedEffect(row.icon, row.sourceUrl, repository, dimensions) {
        val icon = row.icon?.takeUnless { it.isBlank() } ?: return@LaunchedEffect
        if (dimensions.width <= 0 || dimensions.height <= 0) return@LaunchedEffect
        val loaded =
            try {
                repository.load(icon, row.sourceUrl, dimensions.width, dimensions.height, false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        if (loaded != null)
            try {
                resource = loaded
                awaitCancellation()
            } finally {
                resource = null
                loaded.release()
            }
    }
    Box(modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).onSizeChanged { dimensions = it }) {
        val current = resource
        if (current == null)
            Image(
                painterResource(R.drawable.image_rss),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        else {
            val painter = remember(current) { LifecycleDrawablePainter(current) }
            val owner = LocalLifecycleOwner.current
            DisposableEffect(owner, painter) {
                fun update() {
                    if (owner.lifecycle.currentState == Lifecycle.State.RESUMED) painter.start()
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
            Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
