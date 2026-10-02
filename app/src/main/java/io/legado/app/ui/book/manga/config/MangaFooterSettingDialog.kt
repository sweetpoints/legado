package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
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
import io.legado.app.data.preferences.AppMangaFooterSettingsRepository
import io.legado.app.utils.setLayout

class MangaFooterSettingDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<MangaFooterSettingsViewModel> {
        viewModelFactory { initializer {
            MangaFooterSettingsViewModel(AppMangaFooterSettingsRepository(), createSavedStateHandle())
        } }
    }
    // Preserve the previous public getter without exposing mutable UI state to callers.
    val config: MangaFooterConfig get() = viewModel.state.value.toConfig()

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable override fun Content() {
        MangaFooterSettingsRoute(viewModel, Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .8f).dp))
    }

    override fun onDismiss(dialog: DialogInterface) {
        viewModel.saveOnDismiss(isChangingConfigurations = activity?.isChangingConfigurations == true)
        super.onDismiss(dialog)
    }
}
