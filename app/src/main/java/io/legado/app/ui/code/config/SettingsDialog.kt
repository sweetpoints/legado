package io.legado.app.ui.code.config

import android.content.Context
import android.content.DialogInterface
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
import io.legado.app.data.preferences.AppCodeSettingsPreferences

class SettingsDialog() : BaseComposeDialogFragment() {
    private var providedCallback: CallBack? = null
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context, callBack: CallBack) : this() { providedCallback = callBack }
    private val callback: CallBack? get() = providedCallback ?: parentFragment as? CallBack ?: activity as? CallBack
    private val model by viewModels<CodeSettingsViewModel> {
        viewModelFactory { initializer { CodeSettingsViewModel(AppCodeSettingsPreferences(requireContext()), createSavedStateHandle()) } }
    }
    @Composable override fun Content() {
        CodeSettingsRoute(model, { font, auto -> callback?.upEdit(fontSize = font, autoComplete = auto) },
            Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f))
    }
    override fun onDismiss(dialog: DialogInterface) {
        model.onDismiss(activity?.isChangingConfigurations == true)?.let { callback?.upEdit(editNonPrintable = it) }
        super.onDismiss(dialog)
    }
    override fun onDetach() { providedCallback = null; super.onDetach() }
    interface CallBack {
        fun upEdit(fontSize: Int? = null, autoComplete: Boolean? = null, autoWarp: Boolean? = null, editNonPrintable: Int? = null)
    }
}
