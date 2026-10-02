package io.legado.app.ui.replace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.ReplaceRuleGroupRepository
import io.legado.app.ui.group.NamedGroupRoute
import io.legado.app.ui.group.NamedGroupViewModel
import io.legado.app.utils.setLayout

class GroupManageDialog : BaseComposeDialogFragment() {
    private val model by viewModels<NamedGroupViewModel> {
        viewModelFactory { initializer { NamedGroupViewModel(ReplaceRuleGroupRepository(), createSavedStateHandle()) } }
    }
    override fun onStart() { super.onStart(); setLayout(0.9f, 0.9f) }
    @Composable override fun Content() {
        val state by model.state.collectAsStateWithLifecycle()
        SideEffect { isCancelable = !state.busy }
        NamedGroupRoute(model, ::dismissAllowingStateLoss)
    }
}
