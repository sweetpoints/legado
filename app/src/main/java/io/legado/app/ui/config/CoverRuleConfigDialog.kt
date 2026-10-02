package io.legado.app.ui.config

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
import io.legado.app.data.repository.BookCoverRuleRepository
import io.legado.app.utils.setLayout

class CoverRuleConfigDialog : BaseComposeDialogFragment() {
    private val viewModel by viewModels<CoverRuleViewModel> {
        viewModelFactory {
            initializer { CoverRuleViewModel(BookCoverRuleRepository(), createSavedStateHandle()) }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        CoverRuleRoute(viewModel, ::dismiss,
            Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f))
    }
}
