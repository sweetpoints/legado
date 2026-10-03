package io.legado.app.ui.book.read.config

import android.view.ViewGroup
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
import io.legado.app.data.preferences.PreferenceReadAloudSettingsRepository
import io.legado.app.help.IntentHelp
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment

class ReadAloudConfigDialog : BaseComposeDialogFragment(), SpeakEngineDialog.CallBack {
    private val viewModel by
        viewModels<ReadAloudSettingsViewModel> {
            viewModelFactory {
                initializer {
                    ReadAloudSettingsViewModel(
                        PreferenceReadAloudSettingsRepository(requireContext()),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        ReadAloudSettingsRoute(
            viewModel,
            Color(requireContext().backgroundColor),
            ::navigate,
            Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp),
        )
    }

    private fun navigate(destination: ReadAloudSettingsDestination): Boolean {
        if (!isAdded || childFragmentManager.isStateSaved) return false
        when (destination) {
            ReadAloudSettingsDestination.Controls -> showDialogFragment(ReadAloudControlsDialog())
            ReadAloudSettingsDestination.Engine -> showDialogFragment(SpeakEngineDialog())
            ReadAloudSettingsDestination.SystemTts -> IntentHelp.openTTSSetting()
        }
        return true
    }

    override fun upSpeakEngineSummary() {
        viewModel.refreshEngine()
    }
}
