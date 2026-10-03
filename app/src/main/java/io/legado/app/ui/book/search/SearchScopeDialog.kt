package io.legado.app.ui.book.search

import android.content.DialogInterface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.DefaultSearchScopeRepository
import io.legado.app.data.repository.RoomSearchScopeStore
import io.legado.app.utils.setLayout

class SearchScopeDialog : BaseComposeDialogFragment() {
    val callback: Callback
        get() = parentFragment as? Callback ?: activity as Callback

    private val model by
        viewModels<SearchScopeViewModel> {
            viewModelFactory {
                initializer {
                    SearchScopeViewModel(
                        DefaultSearchScopeRepository(RoomSearchScopeStore()),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    @Composable
    override fun Content() {
        SearchScopeRoute(
            model,
            { isAdded && !parentFragmentManager.isStateSaved },
            { result ->
                try {
                    if (result.confirm)
                        callback.onSearchScopeOk(
                            SearchScope(result.scope),
                            arguments?.getString(REQUEST_ID),
                        )
                } finally {
                    dismissAllowingStateLoss()
                }
            },
            Modifier.fillMaxSize(),
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        val owner = parentFragment as? Callback ?: activity as? Callback
        owner?.onSearchScopeDismiss(arguments?.getString(REQUEST_ID))
        super.onDismiss(dialog)
    }

    interface Callback {
        fun onSearchScopeOk(searchScope: SearchScope)

        fun onSearchScopeOk(searchScope: SearchScope, requestId: String?) {
            onSearchScopeOk(searchScope)
        }

        fun onSearchScopeDismiss(requestId: String?) = Unit
    }

    companion object {
        const val REQUEST_ID = "searchScopeRequestId"
    }
}
