package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.preferences.AppTipSettingsRepository
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment

class TipConfigDialog : BaseComposeDialogFragment(), FontSelectDialog.CallBack {
    companion object {
        const val TIP_COLOR = 7897
        const val TIP_DIVIDER_COLOR = 7898
        const val TITLE_NUMBER_COLOR = 7899
        const val TITLE_COLOR = 7900
    }
    private val viewModel by viewModels<TipSettingsViewModel> {
        viewModelFactory { initializer { TipSettingsViewModel(AppTipSettingsRepository(), createSavedStateHandle()) } }
    }
    override fun onStart() {
        super.onStart()
        viewModel.refresh()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        observeEvent<String>(EventBus.TIP_COLOR) { viewModel.refresh() }
    }
    @Composable override fun Content() {
        TipSettingsRoute(viewModel, { showDialogFragment<FontSelectDialog>() },
            Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .8f).dp))
    }
    override val curFontPath get() = viewModel.state.value.settings.titleFont
    override val selectSystemTypefaceOnDefault = false
    override fun selectFont(path: String) = viewModel.setFont(path)
}

private const val TITLE_LINE_SPACING_MIN = -20
private const val TITLE_LINE_SPACING_MAX = 30

internal fun titleLineSpacingToProgress(spacing: Int): Int {
    return (spacing.coerceIn(TITLE_LINE_SPACING_MIN, TITLE_LINE_SPACING_MAX) -
        TITLE_LINE_SPACING_MIN)
}

internal fun titleLineSpacingFromProgress(progress: Int): Int {
    return (progress + TITLE_LINE_SPACING_MIN)
        .coerceIn(TITLE_LINE_SPACING_MIN, TITLE_LINE_SPACING_MAX)
}

internal fun titleLineSpacingDisplayValue(progress: Int): String {
    return (titleLineSpacingFromProgress(progress) / 10f).toString()
}

private const val TITLE_NUMBER_SPACING_MIN = -50
private const val TITLE_NUMBER_SPACING_MAX = 100

internal fun titleNumberSpacingToProgress(spacing: Int): Int {
    return spacing.coerceIn(TITLE_NUMBER_SPACING_MIN, TITLE_NUMBER_SPACING_MAX) -
        TITLE_NUMBER_SPACING_MIN
}

internal fun titleNumberSpacingFromProgress(progress: Int): Int {
    return (progress + TITLE_NUMBER_SPACING_MIN)
        .coerceIn(TITLE_NUMBER_SPACING_MIN, TITLE_NUMBER_SPACING_MAX)
}

internal fun tipTextSizeToProgress(textSize: Int): Int {
    return textSize.coerceIn(ReadTipConfig.minTextSize, ReadTipConfig.maxTextSize) -
        ReadTipConfig.minTextSize
}

internal fun tipTextSizeFromProgress(progress: Int): Int {
    return (progress + ReadTipConfig.minTextSize)
        .coerceIn(ReadTipConfig.minTextSize, ReadTipConfig.maxTextSize)
}
