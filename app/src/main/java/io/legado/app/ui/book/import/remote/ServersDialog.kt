package io.legado.app.ui.book.import.remote

import android.content.DialogInterface
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.RoomRemoteServerListRepository
import io.legado.app.utils.showDialogFragment

class ServersDialog : BaseComposeDialogFragment() {
    val viewModel by
        viewModels<ServersViewModel> {
            viewModelFactory {
                initializer {
                    ServersViewModel(RoomRemoteServerListRepository(), createSavedStateHandle())
                }
            }
        }

    override fun onStart() {
        super.onStart()
        dialog
            ?.window
            ?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    @Composable
    override fun Content() {
        ServersRoute(
            viewModel,
            { showDialogFragment(ServerConfigDialog()) },
            { showDialogFragment(ServerConfigDialog(it)) },
            ::dismissAllowingStateLoss,
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity?.isChangingConfigurations != true)
            (activity as? Callback)?.onDialogDismiss("serversDialog")
    }

    interface Callback {
        fun onDialogDismiss(tag: String)
    }
}
