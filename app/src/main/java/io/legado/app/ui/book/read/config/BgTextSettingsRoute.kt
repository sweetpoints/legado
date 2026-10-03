package io.legado.app.ui.book.read.config

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.ReaderBackgroundPreviewRepository
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.SvgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BgTextSettingsRoute(
    viewModel: BgTextSettingsViewModel,
    previews: ReaderBackgroundPreviewRepository,
    onEffect: (BgTextEffect, () -> Unit) -> Boolean,
    background: Color,
    foreground: Color,
    secondary: Color,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by
        owner.lifecycle.currentStateFlow.collectAsStateWithLifecycle(
            minActiveState = Lifecycle.State.CREATED
        )
    val effectHandler by rememberUpdatedState(onEffect)
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    LaunchedEffect(state.pending.firstOrNull()?.id, state.finished, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
        if (state.finished) {
            effectHandler(BgTextEffect(-1, BgTextAction.Close), {})
            return@LaunchedEffect
        }
        val effect = state.pending.firstOrNull() ?: return@LaunchedEffect
        effectHandler(effect) { viewModel.completed(effect.id) }
    }
    BgTextSettingsScreen(
        state,
        { intent ->
            when (intent) {
                is BgTextSettingsIntent.Slider -> viewModel.slider(intent.slider, intent.value)
                BgTextSettingsIntent.AlphaFinished -> viewModel.alphaFinished()
                is BgTextSettingsIntent.DarkStatus -> viewModel.darkStatus(intent.value)
                is BgTextSettingsIntent.UnderlineBody -> viewModel.underlineBody(intent.value)
                is BgTextSettingsIntent.UnderlineTitle -> viewModel.underlineTitle(intent.value)
                is BgTextSettingsIntent.UnderlineMode -> viewModel.underlineMode(intent.value)
                is BgTextSettingsIntent.Asset -> viewModel.asset(intent.name)
                is BgTextSettingsIntent.Picker -> viewModel.picker(intent.action)
                is BgTextSettingsIntent.Editor -> viewModel.edit(intent.kind)
                BgTextSettingsIntent.CancelEditor -> viewModel.dismissEditor()
                BgTextSettingsIntent.ConfirmEditor -> viewModel.confirmEditor()
                is BgTextSettingsIntent.Text ->
                    viewModel.text(intent.text, intent.start, intent.end)
                is BgTextSettingsIntent.Default -> viewModel.restoreDefault(intent.index)
                BgTextSettingsIntent.DeletePreset -> viewModel.deletePreset()
                is BgTextSettingsIntent.Color -> viewModel.openColor(intent.color)
                is BgTextSettingsIntent.ColorChannel ->
                    viewModel.colorChannel(intent.channel, intent.value)
                BgTextSettingsIntent.ResetReviewColor -> viewModel.resetReviewColor()
                is BgTextSettingsIntent.SaveTemplate ->
                    viewModel.templateName(intent.defaultName, intent.template)
                is BgTextSettingsIntent.ApplyTemplate -> viewModel.templateApply(intent.template)
                is BgTextSettingsIntent.DeleteTemplateEditor ->
                    viewModel.templateDeleteEditor(intent.template)
                BgTextSettingsIntent.DeleteTemplate -> viewModel.templateDelete()
            }
        },
        background,
        foreground,
        secondary,
        modifier,
        assetPreview = { name, bounds ->
            val configuration = remember(name) { backgroundAssetConfiguration(name) }
            ReadStylePresetPreview(configuration, previews, bounds)
        },
        svgPreview = { svg, bounds -> BgTextSvgPreview(svg, bounds) },
    )
}

@Composable
internal fun BgTextSvgPreview(svg: String, modifier: Modifier) {
    val image by
        produceState<Bitmap?>(null, svg) {
            value = null
            value =
                withContext(Dispatchers.Default) {
                    SvgUtils.createBitmapFromSvgText(svg.replace("{{count}}", "88"), 48, 48)
                }
        }
    image?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit) }
}

internal fun backgroundAssetConfiguration(name: String): String =
    GSON.toJson(
        ReadBookConfig.Config(
            bgType = 1,
            bgStr = name,
            bgTypeNight = 1,
            bgStrNight = name,
            bgTypeEInk = 1,
            bgStrEInk = name,
        )
    )
