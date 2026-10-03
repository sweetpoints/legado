package io.legado.app.ui.book.group

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.RoomBookGroupSelectionRepository
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment

class GroupSelectDialog() : BaseComposeDialogFragment() {
    constructor(groupId: Long, requestCode: Int = -1) : this() {
        arguments =
            Bundle().apply {
                putLong("groupId", groupId)
                putInt("requestCode", requestCode)
            }
    }

    private val viewModel by
        viewModels<BookGroupSelectionViewModel> {
            viewModelFactory {
                initializer {
                    BookGroupSelectionViewModel(
                        RoomBookGroupSelectionRepository(),
                        createSavedStateHandle(),
                        arguments?.getLong("groupId") ?: 0,
                        arguments?.getInt("requestCode", -1) ?: -1,
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        SideEffect { isCancelable = !state.busy }
        BookGroupSelectionRoute(
            viewModel,
            { showDialogFragment(GroupEditDialog()) },
            { showDialogFragment(GroupEditDialog(it.entity())) },
            { (activity as? CallBack)?.upGroup(it.requestCode, it.groupId) },
            ::dismissAllowingStateLoss,
        )
    }

    override fun onPause() {
        viewModel.cancelReorder()
        super.onPause()
    }

    interface CallBack {
        fun upGroup(requestCode: Int, groupId: Long)
    }
}
