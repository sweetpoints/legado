package io.legado.app.ui.dict

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.RoomDictionaryLookupRepository
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

/** The existing word constructor and fragment restoration API remain available. */
class DictDialog() : BaseComposeDialogFragment() {
    constructor(word: String) : this() { arguments = Bundle().apply { putString("word", word) } }
    private val model by viewModels<DictionaryLookupViewModel> {
        viewModelFactory { initializer { DictionaryLookupViewModel(RoomDictionaryLookupRepository(requireContext().applicationContext), createSavedStateHandle(), arguments?.getString("word")) } }
    }
    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    @Composable override fun Content() {
        DictionaryLookupRoute(model, { toastOnUi(R.string.cannot_empty); dismissAllowingStateLoss() },
            { showDialogFragment(PhotoDialog(it)) })
    }
}
