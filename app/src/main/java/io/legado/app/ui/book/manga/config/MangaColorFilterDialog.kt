package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.legado.app.data.preferences.PreferenceMangaColorFilterRepository
import io.legado.app.utils.setLayout

class MangaColorFilterDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<MangaColorFilterViewModel> {
        viewModelFactory { initializer {
            MangaColorFilterViewModel(PreferenceMangaColorFilterRepository(), createSavedStateHandle())
        } }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        MangaColorFilterRoute(viewModel, { (activity as? Callback)?.updateColorFilter(it) },
            Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f))
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (activity?.isChangingConfigurations != true) viewModel.finish()
        super.onDismiss(dialog)
    }

    interface Callback {
        fun updateColorFilter(config: MangaColorFilterConfig)
    }
}
