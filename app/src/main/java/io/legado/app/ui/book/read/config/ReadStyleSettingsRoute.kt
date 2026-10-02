package io.legado.app.ui.book.read.config

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.ReaderBackgroundPreviewRepository

@Composable internal fun ReadStyleSettingsRoute(viewModel: ReadStyleSettingsViewModel,
    previews: ReaderBackgroundPreviewRepository, onAnimation: () -> Unit,
    onDestination: (ReadStyleDestination, () -> Unit) -> Boolean, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val animation by rememberUpdatedState(onAnimation); val destination by rememberUpdatedState(onDestination)
    LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose {} }
    LaunchedEffect(state.pending.firstOrNull()?.id, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
        val effect = state.pending.firstOrNull() ?: return@LaunchedEffect
        fun apply() {
            if (effect.update?.animationChanged == true) animation()
            // Configuration events follow the animation callback, preserving scroll reader positioning.
            viewModel.completed(effect.id)
        }
        if (effect.destination == null) apply()
        else destination(effect.destination, ::apply) // Host acknowledges only when its FragmentManager can open.

    }
    ReadStyleSettingsScreen(state, viewModel::slider, viewModel::preset, viewModel::editPreset, viewModel::addPreset,
        viewModel::shared, viewModel::animation, viewModel::picker, viewModel::pick, viewModel::open, background, foreground, modifier,
        preview = { preset, bounds -> ReadStylePresetPreview(preset.configuration, previews, bounds) })
}
@Composable internal fun ReadStylePresetPreview(configuration: String, repository: ReaderBackgroundPreviewRepository, modifier: Modifier) {
    val image by produceState<Drawable?>(null, configuration, repository) { value = repository.load(configuration, 100, 150) }
    image?.let { drawable ->
        val painter = remember(drawable) { ReadStyleDrawablePainter(drawable) }
        Image(painter, null, modifier, contentScale = ContentScale.Crop)
    }
}
private class ReadStyleDrawablePainter(private val drawable: Drawable) : Painter() {
    override val intrinsicSize = Size(drawable.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: 100f,
        drawable.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: 150f)
    override fun DrawScope.onDraw() {
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}
