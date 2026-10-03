package io.legado.app.ui.rss.article

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.RssArticleImageRepository
import io.legado.app.data.repository.RssArticleRow
import io.legado.app.data.repository.rssArticleImageRatio
import io.legado.app.ui.components.image.LifecycleDrawablePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

/** An image owns its Glide lease and animation callbacks; disposal releases both exactly once. */
@Composable
fun RssArticleImage(
    row: RssArticleRow,
    repository: RssArticleImageRepository,
    modifier: Modifier = Modifier,
    natural: Boolean = false,
    keepEmpty: Boolean = false,
    requestRevision: Long = 0,
) {
    val source = row.image?.takeUnless { it.isBlank() }
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    var resource by
        remember(source, row.origin, repository, requestRevision) {
            mutableStateOf<AnimatedDrawableResource?>(null)
        }
    var ratio by
        remember(source, row.origin, repository, requestRevision) { mutableStateOf<Float?>(null) }
    var failed by
        remember(source, row.origin, repository, requestRevision) { mutableStateOf(false) }
    val requestedHeight = if (natural) 0 else dimensions.height
    LaunchedEffect(
        source,
        row.origin,
        repository,
        dimensions.width,
        requestedHeight,
        natural,
        requestRevision,
    ) {
        if (source == null || dimensions.width <= 0 || !natural && requestedHeight <= 0)
            return@LaunchedEffect
        val loaded =
            try {
                if (natural) ratio = repository.ratio(source)?.takeIf { it.isFinite() && it > 0 }
                repository.load(source, row.origin, dimensions.width, requestedHeight, natural)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
                return@LaunchedEffect
            }
        if (loaded == null) {
            failed = true
            return@LaunchedEffect
        }
        try {
            resource = loaded
            if (natural)
                ratio =
                    rssArticleImageRatio(
                        loaded.drawable.intrinsicWidth,
                        loaded.drawable.intrinsicHeight,
                    ) ?: ratio
            awaitCancellation()
        } finally {
            resource = null
            loaded.release()
        }
    }
    if (source == null && !keepEmpty || failed && !keepEmpty && !natural) return
    val sized =
        if (natural) ratio?.let { modifier.aspectRatio(1f / it) } ?: modifier.height(1.dp)
        else modifier
    Box(sized.onSizeChanged { dimensions = it }.testTag("rss-article-image-${row.key}")) {
        val current = resource
        if (current != null) {
            val painter = remember(current) { LifecycleDrawablePainter(current) }
            val owner = LocalLifecycleOwner.current
            DisposableEffect(painter, owner) {
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
            Image(
                painter,
                stringResource(R.string.img_cover),
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (keepEmpty)
            Image(painterResource(R.drawable.transparent_placeholder), null, Modifier.fillMaxSize())
    }
}
