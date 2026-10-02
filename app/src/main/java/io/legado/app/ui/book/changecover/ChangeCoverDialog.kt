package io.legado.app.ui.book.changecover

import android.content.DialogInterface
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
import io.legado.app.data.repository.AppChangeCoverStore
import io.legado.app.data.repository.DefaultChangeCoverRepository
import io.legado.app.utils.setLayout

class ChangeCoverDialog() : BaseComposeDialogFragment() {
    constructor(name: String, author: String) : this() {
        arguments = Bundle().apply { putString("name", name); putString("author", author) }
    }
    private val callBack: CallBack? get() = activity as? CallBack
    private val model by viewModels<ChangeCoverComposeViewModel> {
        viewModelFactory { initializer { ChangeCoverComposeViewModel(DefaultChangeCoverRepository(AppChangeCoverStore(requireContext())),
            createSavedStateHandle(), arguments?.getString("name").orEmpty(), arguments?.getString("author").orEmpty()) } }
    }
    override fun onStart() { super.onStart(); setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT) }
    override fun onCancel(dialog: DialogInterface) { model.stop(); super.onCancel(dialog) }
    @Composable override fun Content() {
        ChangeCoverRoute(model, { isAdded && !parentFragmentManager.isStateSaved }, ::changeTo,
            { dismissAllowingStateLoss() }, Modifier.fillMaxSize())
    }
    fun changeTo(coverUrl: String) {
        try { callBack?.coverChangeTo(coverUrl) } finally { dismissAllowingStateLoss() }
    }
    interface CallBack { fun coverChangeTo(coverUrl: String) }
}
