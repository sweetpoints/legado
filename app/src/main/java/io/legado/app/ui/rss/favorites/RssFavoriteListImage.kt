package io.legado.app.ui.rss.favorites

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.RssFavoriteImageRepository
import io.legado.app.data.repository.RssFavoriteRow
import io.legado.app.ui.components.image.LifecycleDrawablePainter
import kotlinx.coroutines.awaitCancellation

@Composable internal fun RssFavoriteListImage(row: RssFavoriteRow, repository: RssFavoriteImageRepository) {
    val source = row.image?.takeUnless { it.isBlank() } ?: return
    var image by remember(source, row.origin, repository) { mutableStateOf<AnimatedDrawableResource?>(null) }
    val width = with(LocalDensity.current) { 94.dp.roundToPx() }; val height = with(LocalDensity.current) { 68.dp.roundToPx() }
    LaunchedEffect(source, row.origin, repository, width, height) {
        val loaded = repository.load(source, row.origin, width, height) ?: return@LaunchedEffect
        try { image = loaded; awaitCancellation() } finally { image = null; loaded.release() }
    }
    image?.let { resource ->
        val painter = remember(resource) { LifecycleDrawablePainter(resource) }; val owner = LocalLifecycleOwner.current
        DisposableEffect(painter, owner) {
            val observer = LifecycleEventObserver { _, _ -> if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) painter.start() else painter.stop() }
            owner.lifecycle.addObserver(observer); if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) painter.start()
            onDispose { owner.lifecycle.removeObserver(observer); painter.stop() }
        }
        Image(painter, stringResource(R.string.img_cover), Modifier.padding(start = 16.dp).size(94.dp, 68.dp).testTag("rss-favorite-image-${row.id}"), contentScale = ContentScale.Crop)
    }
}
