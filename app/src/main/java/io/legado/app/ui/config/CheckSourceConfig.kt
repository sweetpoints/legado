package io.legado.app.ui.config

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppSourceCheckSettingsRepository
import io.legado.app.utils.setLayout

class CheckSourceConfig : BaseComposeDialogFragment() {
    private val model by
        viewModels<SourceCheckSettingsViewModel> {
            viewModelFactory {
                initializer {
                    SourceCheckSettingsViewModel(
                        AppSourceCheckSettingsRepository(requireContext()),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        SourceCheckSettingsRoute(
            model,
            { isAdded && !parentFragmentManager.isStateSaved },
            ::dismiss,
            { isCancelable = it },
        )
    }
}
