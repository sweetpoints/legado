package io.legado.app.ui.config

import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import android.os.Bundle
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.PreferencesBackgroundBlurRepository
import io.legado.app.utils.setLayout

class BackgroundBlurDialog : BaseComposeDialogFragment() {
    private val model by
        viewModels<BackgroundBlurViewModel> {
            viewModelFactory {
                initializer {
                    BackgroundBlurViewModel(
                        PreferencesBackgroundBlurRepository(requireContext()),
                        createSavedStateHandle(),
                        requireArguments().getBoolean(NIGHT),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        val state by model.state.collectAsStateWithLifecycle()
        SideEffect { isCancelable = !state.saving }
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(model, owner) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { value ->
                    if (value.finished) {
                        model.claim()?.let { applied ->
                            if (applied)
                                parentFragmentManager.setFragmentResult(
                                    RESULT,
                                    Bundle().apply { putBoolean(NIGHT, model.night) },
                                )
                        }
                        dismiss()
                    }
                }
            }
        }
        BackgroundBlurScreen(
            state,
            model::radius,
            model::save,
            model::cancel,
            model::retry,
            Modifier.fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f),
        )
    }

    companion object {
        const val RESULT = "background-blur-result"
        const val NIGHT = "background-blur-night"

        fun newInstance(night: Boolean) =
            BackgroundBlurDialog().apply { arguments = Bundle().apply { putBoolean(NIGHT, night) } }
    }
}
