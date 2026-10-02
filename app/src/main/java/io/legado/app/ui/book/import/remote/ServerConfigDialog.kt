package io.legado.app.ui.book.import.remote

import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.RoomRemoteServerEditorRepository

class ServerConfigDialog() : BaseComposeDialogFragment() {
    constructor(id: Long) : this() { arguments = Bundle().apply { putLong("id", id) } }
    private val viewModel by viewModels<ServerConfigViewModel> {
        viewModelFactory { initializer { ServerConfigViewModel(RoomRemoteServerEditorRepository(), createSavedStateHandle(), arguments?.getLong("id")) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); isCancelable = false }
    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    @Composable override fun Content() { ServerConfigRoute(viewModel, ::dismissAllowingStateLoss) }
    override fun dismiss() { viewModel.close() }
}
