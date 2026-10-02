package io.legado.app.ui.video.config

import android.content.Context
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
import io.legado.app.data.preferences.AppVideoSettingsRepository
import io.legado.app.utils.setLayout

class SettingsDialog() : BaseComposeDialogFragment() {
    // Preserve callers while allowing FragmentManager to recreate the dialog without retaining a Context.
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context, callBack: CallBack? = null) : this()

    private val viewModel by viewModels<VideoSettingsViewModel> {
        viewModelFactory { initializer {
            VideoSettingsViewModel(AppVideoSettingsRepository(), createSavedStateHandle())
        } }
    }
    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    @Composable override fun Content() {
        VideoSettingsRoute(viewModel,
            Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f))
    }
    interface CallBack
}
