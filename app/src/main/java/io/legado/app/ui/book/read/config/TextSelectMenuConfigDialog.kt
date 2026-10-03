package io.legado.app.ui.book.read.config

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.legado.app.data.preferences.PreferenceTextSelectMenuSettingsRepository
import io.legado.app.utils.setLayout

class TextSelectMenuConfigDialog : BaseComposeDialogFragment() {
    private val viewModel by
        viewModels<TextSelectMenuSettingsViewModel> {
            viewModelFactory {
                initializer {
                    TextSelectMenuSettingsViewModel(
                        PreferenceTextSelectMenuSettingsRepository(requireContext()),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        TextSelectMenuSettingsRoute(
            viewModel,
            ::dismiss,
            Modifier.fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f),
        )
    }
}
