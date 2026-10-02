package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.view.ViewGroup
import android.view.WindowManager
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
import io.legado.app.data.preferences.AppMangaEpaperPreferences
import io.legado.app.utils.setLayout

class MangaEpaperDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<MangaEpaperViewModel> {
        viewModelFactory {
            initializer { MangaEpaperViewModel(AppMangaEpaperPreferences(), createSavedStateHandle()) }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        MangaEpaperRoute(viewModel, { (activity as? Callback)?.updateEepaper(it) },
            Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f))
    }

    override fun onDismiss(dialog: DialogInterface) {
        viewModel.onDismiss(activity?.isChangingConfigurations == true)
        super.onDismiss(dialog)
    }

    interface Callback {
        fun updateEepaper(value: Int)
    }
}
