package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.preferences.AppReadStyleSettingsRepository
import io.legado.app.data.repository.AppReaderBackgroundPreviewRepository
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.showDialogFragment

class ReadStyleDialog : BaseComposeDialogFragment(), FontSelectDialog.CallBack {
    private val model by viewModels<ReadStyleSettingsViewModel> {
        viewModelFactory { initializer { ReadStyleSettingsViewModel(AppReadStyleSettingsRepository(), createSavedStateHandle()) } }
    }
    private val previews by lazy { AppReaderBackgroundPreviewRepository(requireContext()) }
    private val visibility = PaddingPanelVisibility()
    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            attributes = attributes.apply { dimAmount = 0f; gravity = Gravity.BOTTOM }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        val host = activity as? ReadBookActivity ?: return
        visibility.acquire(object : PaddingPanelVisibility.Owner {
            override var bottomDialog: Int
                get() = host.bottomDialog
                set(value) { host.bottomDialog = value }
        })
    }
    @Composable override fun Content() {
        val background = requireContext().bottomBackground
        val foreground = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(background))
        ReadStyleSettingsRoute(model, previews, { (activity as? ReadBookActivity)?.upPageAnim() }, ::open,
            Color(background), Color(foreground), Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp))
    }
    private fun open(destination: ReadStyleDestination, beforeOpen: () -> Unit): Boolean {
        if (!isAdded || childFragmentManager.isStateSaved) return false
        beforeOpen()
        when (destination) {
            ReadStyleDestination.Padding -> { dismissAllowingStateLoss(); (activity as? ReadBookActivity)?.showPaddingConfig() }
            ReadStyleDestination.Background -> { dismissAllowingStateLoss(); (activity as? ReadBookActivity)?.showBgTextConfig() }
            ReadStyleDestination.Tip -> if (childFragmentManager.findFragmentByTag("tipConfigDialog") == null) TipConfigDialog().show(childFragmentManager, "tipConfigDialog")
            ReadStyleDestination.Font -> if (childFragmentManager.findFragmentByTag("FontSelectDialog") == null) showDialogFragment<FontSelectDialog>()
        }
        return true
    }
    override val curFontPath: String get() = model.state.value.settings.font
    override fun selectFont(path: String) { model.font(path) }
    override fun onDestroyView() { visibility.release(); super.onDestroyView() }
    override fun onDismiss(dialog: DialogInterface) {
        model.dismissed(activity?.isChangingConfigurations == true)
        visibility.release(); super.onDismiss(dialog)
    }
}

private const val LINE_SPACING_CONFIG_MIN = -10
private const val LINE_SPACING_CONFIG_MAX = 40
private const val LINE_SPACING_PROGRESS_OFFSET = 10

internal fun lineSpacingToProgress(value: Int): Int {
    return (value + LINE_SPACING_PROGRESS_OFFSET)
        .coerceIn(0, LINE_SPACING_CONFIG_MAX - LINE_SPACING_CONFIG_MIN)
}

internal fun lineSpacingFromProgress(progress: Int): Int {
    return (progress - LINE_SPACING_PROGRESS_OFFSET)
        .coerceIn(LINE_SPACING_CONFIG_MIN, LINE_SPACING_CONFIG_MAX)
}

internal fun lineSpacingDisplayValue(progress: Int): String {
    return ((lineSpacingFromProgress(progress) - 10) / 10f).toString()
}
