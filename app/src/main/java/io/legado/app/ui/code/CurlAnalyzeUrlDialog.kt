package io.legado.app.ui.code

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppCurlDraftStore
import io.legado.app.data.repository.DefaultCurlConversionRepository
import io.legado.app.help.IntentData
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi

class CurlAnalyzeUrlDialog() : BaseComposeDialogFragment() {
    constructor(input: String, canInsert: Boolean) : this() {
        arguments =
            Bundle().apply {
                putString(ARG_INPUT, IntentData.put(input))
                putBoolean(ARG_CAN_INSERT, canInsert)
            }
    }

    private val viewModel by
        viewModels<CurlConversionViewModel> {
            viewModelFactory {
                initializer {
                    CurlConversionViewModel(
                        DefaultCurlConversionRepository(AppCurlDraftStore(requireContext())),
                        createSavedStateHandle(),
                        arguments?.getString(ARG_INPUT),
                        arguments?.getBoolean(ARG_CAN_INSERT) == true,
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onStop() {
        viewModel.flush()
        super.onStop()
    }

    @Composable
    override fun Content() {
        CurlConversionRoute(
            viewModel,
            { isAdded && !parentFragmentManager.isStateSaved },
            { requireContext().sendToClip(it) },
            { text, result ->
                val callback = parentFragment as? Callback ?: activity as? Callback
                if (callback == null) result(false)
                else callback.onCurlAnalyzeUrlInsert(text, result)
            },
            { toastOnUi(it) },
            { dismissAllowingStateLoss() },
            Modifier.fillMaxSize(),
        )
    }

    override fun dismiss() {
        viewModel.finish()
    }

    interface Callback {
        fun onCurlAnalyzeUrlInsert(text: String, onResult: (Boolean) -> Unit)
    }

    private companion object {
        const val ARG_INPUT = "input"
        const val ARG_CAN_INSERT = "canInsert"
    }
}
