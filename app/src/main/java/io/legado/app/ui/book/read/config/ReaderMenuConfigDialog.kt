package io.legado.app.ui.book.read.config

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
import io.legado.app.data.preferences.PreferenceReaderMenuSettingsRepository
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.setLayout

/** Configure the first-level actions and keep unchecked actions reachable in More. */
class ReaderMenuConfigDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<ReaderMenuConfigViewModel> {
        viewModelFactory { initializer {
            ReaderMenuConfigViewModel(PreferenceReaderMenuSettingsRepository(requireContext()), createSavedStateHandle())
        } }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        ReaderMenuConfigRoute(viewModel, ::dismiss,
            { (activity as? ReadBookActivity)?.refreshReaderMenu() },
            Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.9f))
    }
}
