package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.data.repository.HighlightChannel
import io.legado.app.data.repository.HighlightStyleRepository
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyle.*
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.showDialogFragment
import kotlin.math.roundToInt

/** The existing bottom-sheet window shell hosts only Compose content. */
class HighlightStyleDialog : BottomSheetDialogFragment(), ShadowEditDialog.Callback,
    UnderlineEditDialog.Callback, FontSelectDialog.CallBack {
    interface StyleHost {
        fun currentHighlightStyle(): HighlightStyle
        fun onHighlightStyleChanged(style: HighlightStyle)
        fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean)
    }
    private val styleHost get() = resolveStyleHost(parentFragment, activity)
    private val viewModel by viewModels<HighlightStyleViewModel> {
        viewModelFactory { initializer { HighlightStyleViewModel(createSavedStateHandle()) } }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            id = io.legado.app.R.id.compose_dialog_content
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { LegadoComposeTheme {
                HighlightStyleRoute(viewModel, ReadBookConfig.textSize, (ReadBookConfig.letterSpacing * 100).roundToInt(),
                    { isAdded && !childFragmentManager.isStateSaved }, { currentStyle() }, { style, fontChanged ->
                        if (fontChanged) ChapterProvider.invalidateHighlightTypeface(style.resolvedFontPath)
                        styleHost?.onHighlightStyleChanged(style)
                    }, { channel, style ->
                        styleHost?.pickHighlightColor(channelDialogId(channel), HighlightStyleRepository().color(style, channel),
                            channel == HighlightChannel.Fill || channel == HighlightChannel.Shadow)
                    }, { if (childFragmentManager.findFragmentByTag(FontSelectDialog::class.simpleName) == null) showDialogFragment<FontSelectDialog>() },
                    { shadow -> if (childFragmentManager.findFragmentByTag(ShadowEditDialog::class.simpleName) == null) ShadowEditDialog.show(childFragmentManager, shadow) },
                    { underline -> if (childFragmentManager.findFragmentByTag(UnderlineEditDialog::class.simpleName) == null) UnderlineEditDialog.show(childFragmentManager, underline) },
                    Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f))
            } }
        }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = styleHost ?: run { dismiss(); return }
        viewModel.attach(host.currentHighlightStyle())
    }
    override fun onStart() { super.onStart(); styleHost?.let { viewModel.attach(it.currentHighlightStyle()) } }
    private fun currentStyle() = styleHost?.currentHighlightStyle() ?: HighlightStyle()
    fun refresh() { if (view != null) viewModel.refresh(currentStyle()) }
    override val curFontPath: String get() = currentStyle().resolvedFontPath
    override val selectSystemTypefaceOnDefault = false
    override fun selectFont(path: String) { viewModel.selectFont(path) }
    override fun onShadowChanged(shadow: Shadow) { viewModel.shadow(shadow) }
    override fun onUnderlineChanged(underline: Underline) { viewModel.underline(underline) }
    private fun channelDialogId(channel: HighlightChannel): Int = when (channel) {
        HighlightChannel.Fill -> HL_FILL
        HighlightChannel.Text -> HL_TEXT
        HighlightChannel.Underline -> HL_UNDERLINE
        HighlightChannel.Strike -> HL_STRIKE
        HighlightChannel.Box -> HL_BOX
        HighlightChannel.Emphasis -> HL_EMPHASIS
        HighlightChannel.Shadow -> HL_SHADOW
        else -> -1
    }
    companion object {
        const val HL_FILL = 8101
        const val HL_TEXT = 8102
        const val HL_UNDERLINE = 8103
        const val HL_STRIKE = 8104
        const val HL_BOX = 8105
        const val HL_EMPHASIS = 8106
        const val HL_SHADOW = 8107

        private const val NO_COLOR = -1
        private val DEFAULT_FILL_COLOR = 0x80FFF176.toInt()
        private val DEFAULT_TEXT_COLOR = 0xFFE53935.toInt()
        private val DEFAULT_SWATCH_COLOR = 0xFF888888.toInt()

        fun resolveStyleHost(parent: Any?, activity: Any?): StyleHost? {
            return (parent as? StyleHost) ?: (activity as? StyleHost)
        }

        fun applyChannelColor(
            style: HighlightStyle,
            dialogId: Int,
            color: Int
        ): HighlightStyle = when (dialogId) {
            HL_FILL -> style.copy(fill = color)
            HL_TEXT -> style.copy(textColor = color)
            HL_UNDERLINE -> style.copy(
                underline = (style.underline ?: Underline()).copy(color = color)
            )
            HL_STRIKE -> style.copy(strike = Deco(color))
            HL_BOX -> style.copy(box = Deco(color))
            HL_EMPHASIS -> style.copy(emphasis = Deco(color))
            HL_SHADOW -> style.copy(
                shadow = (style.shadow ?: Shadow()).copy(color = color)
            )
            else -> style
        }

        internal fun shouldOpenShadowEditor(
            previousStyle: HighlightStyle,
            newStyle: HighlightStyle
        ): Boolean = previousStyle.shadow == null && newStyle.shadow != null
    }
}
