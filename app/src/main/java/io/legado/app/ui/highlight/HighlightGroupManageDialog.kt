package io.legado.app.ui.highlight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.RoomHighlightGroupRepository
import io.legado.app.model.ReadBook
import io.legado.app.utils.setLayout

class HighlightGroupManageDialog : BaseComposeDialogFragment() {
    private val model by
        viewModels<HighlightGroupViewModel> {
            viewModelFactory {
                initializer {
                    HighlightGroupViewModel(
                        RoomHighlightGroupRepository(),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    @Composable
    override fun Content() {
        val state by model.state.collectAsStateWithLifecycle()
        SideEffect { isCancelable = !state.busy }
        HighlightGroupRoute(
            model,
            { isAdded && !parentFragmentManager.isStateSaved },
            ReadBook::upHighlightRules,
            { dismissAllowingStateLoss() },
        )
    }
}
