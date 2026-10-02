package io.legado.app.ui.book.group

import androidx.compose.runtime.*
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.RoomBookGroupManagementRepository
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment

class GroupManageDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<BookGroupManagementViewModel> {
        viewModelFactory { initializer { BookGroupManagementViewModel(RoomBookGroupManagementRepository(), createSavedStateHandle()) } }
    }
    override fun onStart() { super.onStart(); setLayout(0.9f, 0.9f) }
    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        SideEffect { isCancelable = !state.busy }
        BookGroupManagementRoute(viewModel, { showDialogFragment(GroupEditDialog()) },
            { showDialogFragment(GroupEditDialog(it.entity())) }, ::dismissAllowingStateLoss)
    }
    override fun onPause() { viewModel.cancelReorder(); super.onPause() }
}
