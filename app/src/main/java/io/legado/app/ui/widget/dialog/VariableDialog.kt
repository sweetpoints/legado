package io.legado.app.ui.widget.dialog

import io.legado.app.utils.resizeForIme

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.ui.widget.dialog.variable.VariableRoute
import io.legado.app.ui.widget.dialog.variable.VariableViewModel
import io.legado.app.utils.setLayout

class VariableDialog() : BaseComposeDialogFragment() {
    constructor(title: String, key: String, variable: String?, comment: String) : this() {
        arguments =
            Bundle().apply {
                putString("title", title)
                putString("key", key)
                putString("variable", variable)
                putString("comment", comment)
            }
    }

    private val viewModel by
        viewModels<VariableViewModel> {
            viewModelFactory { initializer { VariableViewModel(createSavedStateHandle()) } }
        }
    val callback
        get() = (parentFragment as? Callback) ?: (activity as? Callback)

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        view?.setBackgroundColor(Color.TRANSPARENT)
        if (arguments == null) dismissAllowingStateLoss()
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            resizeForIme()
        }
    }

    override fun onResume() {
        super.onResume()
        // The fullscreen backdrop remains transparent, including the base's e-ink border pass.
        view?.setBackgroundColor(Color.TRANSPARENT)
    }

    @Composable
    override fun Content() {
        VariableRoute(
            viewModel,
            { callback?.setVariable(it.key, it.variable) },
            ::dismissAllowingStateLoss,
        )
    }

    interface Callback {
        fun setVariable(key: String, variable: String?)
    }
}
