package io.legado.app.ui.book.group

import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.repository.BookGroupEditorSnapshot
import io.legado.app.data.repository.RoomBookGroupEditorRepository
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.setLayout

class GroupEditDialog() : BaseComposeDialogFragment() {
    constructor(bookGroup: BookGroup? = null) : this() {
        arguments = Bundle().apply { putParcelable("group", bookGroup?.copy()) }
    }

    private val viewModel by
        viewModels<BookGroupEditorViewModel> {
            viewModelFactory {
                initializer {
                    @Suppress("DEPRECATION")
                    val initial =
                        arguments
                            ?.getParcelable<BookGroup>("group")
                            ?.let(BookGroupEditorSnapshot::from)
                    BookGroupEditorViewModel(
                        RoomBookGroupEditorRepository(requireContext().applicationContext),
                        createSavedStateHandle(),
                        initial,
                    )
                }
            }
        }
    private val selectImage =
        registerForActivityResult(HandleFileContract()) {
            viewModel.coverResult(it.uri?.toString())
        }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        SideEffect { isCancelable = !state.saving }
        BookGroupEditorRoute(
            viewModel,
            { selectImage.launch { mode = HandleFileContract.IMAGE } },
            ::dismissAllowingStateLoss,
        )
    }
}
