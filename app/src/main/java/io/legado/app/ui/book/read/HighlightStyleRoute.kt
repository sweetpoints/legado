package io.legado.app.ui.book.read

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.data.repository.HighlightChannel
import io.legado.app.help.HighlightStyle

@Composable
internal fun HighlightStyleRoute(
    viewModel: HighlightStyleViewModel,
    defaultTextSize: Int,
    defaultLetterSpacing: Int,
    canHandle: () -> Boolean,
    currentStyle: () -> HighlightStyle,
    onApply: (HighlightStyle, Boolean) -> Unit,
    onColor: (HighlightChannel, HighlightStyle) -> Unit,
    onFont: () -> Unit,
    onShadow: (HighlightStyle.Shadow) -> Unit,
    onUnderline: (HighlightStyle.Underline) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(canHandle)
    val current by rememberUpdatedState(currentStyle)
    val apply by rememberUpdatedState(onApply)
    val color by rememberUpdatedState(onColor)
    val font by rememberUpdatedState(onFont)
    val shadow by rememberUpdatedState(onShadow)
    val underline by rememberUpdatedState(onUnderline)
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { value ->
                if (!ready()) return@collect
                val effect = value.effects.firstOrNull() ?: return@collect
                // Host effects are synchronous. Consume before platform callbacks can
                // pause/recreate us.
                viewModel.consume(effect.id)
                when (effect.action) {
                    HighlightStyleAction.Apply -> {
                        apply(effect.style, effect.fontChanged)
                        viewModel.acceptHost(current())
                    }
                    HighlightStyleAction.Color -> effect.channel?.let { color(it, effect.style) }
                    HighlightStyleAction.Font -> font()
                    HighlightStyleAction.Shadow -> effect.style.shadow?.let(shadow)
                    HighlightStyleAction.Underline -> effect.style.underline?.let(underline)
                }
            }
        }
    }
    val path = state.style.resolvedFontPath
    val defaultFont = stringResource(R.string.default_font)
    val fontName =
        remember(path, defaultFont) {
            if (path.isEmpty()) defaultFont
            else Uri.decode(path).substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
        }
    HighlightStyleScreen(
        state,
        viewModel.presets,
        fontName,
        viewModel::preset,
        viewModel::toggle,
        viewModel::color,
        viewModel::extra,
        viewModel::tune,
        viewModel::font,
        { setting ->
            viewModel.number(
                setting,
                when (setting) {
                    HighlightNumber.FontSize -> defaultTextSize
                    HighlightNumber.LetterSpacing -> defaultLetterSpacing
                    HighlightNumber.PillPadding -> 100
                },
            )
        },
        viewModel::numberText,
        viewModel::numberValue,
        viewModel::dismissNumber,
        viewModel::saveNumber,
        modifier,
    )
}
