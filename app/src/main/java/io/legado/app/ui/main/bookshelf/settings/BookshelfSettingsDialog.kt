package io.legado.app.ui.main.bookshelf.settings

import android.view.ViewGroup
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
import io.legado.app.data.preferences.PreferenceBookshelfSettingsRepository
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.utils.setLayout

class BookshelfSettingsDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<BookshelfSettingsViewModel> {
        viewModelFactory { initializer { BookshelfSettingsViewModel(PreferenceBookshelfSettingsRepository(requireContext()), createSavedStateHandle()) } }
    }
    override fun onStart() { super.onStart(); setLayout(.9f, ViewGroup.LayoutParams.WRAP_CONTENT) }
    @Composable override fun Content() {
        BookshelfSettingsRoute(viewModel, { effects -> (parentFragment as? BaseBookshelfFragment)?.applySettingsEffects(effects) },
            ::dismiss, Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f))
    }
}
