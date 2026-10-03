package io.legado.app.ui.components.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.MarkdownImageRepository
import io.legado.app.ui.components.image.LifecycleDrawablePainter
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RichTextImage(
    image: RichInline.Image,
    repository: MarkdownImageRepository,
    link: (String) -> Unit,
    inspect: (String) -> Unit,
    tag: String,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("rich-image-$tag")) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(1, 8192)
        var resource by
            remember(image.source, repository) { mutableStateOf<AnimatedDrawableResource?>(null) }
        LaunchedEffect(image.source, repository, width) {
            resource = null
            val loaded = repository.load(image.source, width) ?: return@LaunchedEffect
            try {
                resource = loaded
                awaitCancellation()
            } finally {
                resource = null
                loaded.release()
            }
        }
        resource?.let { value ->
            val painter = remember(value) { LifecycleDrawablePainter(value) }
            val owner = LocalLifecycleOwner.current
            DisposableEffect(painter, owner) {
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
            Image(
                painter,
                image.description,
                Modifier.fillMaxWidth()
                    .aspectRatio(
                        (painter.intrinsicSize.width / painter.intrinsicSize.height).coerceAtLeast(
                            .001f
                        )
                    )
                    .testTag("rich-image-loaded-$tag")
                    .combinedClickable(
                        onClick = { image.link?.let(link) },
                        onLongClick = { inspect(image.source) },
                    ),
                contentScale = ContentScale.Fit,
            )
        } ?: Text(image.description.ifBlank { image.source })
    }
}
