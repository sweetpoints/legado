package io.legado.app.ui.config

import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.AppBottomBarAssignmentRepository
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/** Keeps the import/edit intent contract; staging transactions remain owned by the skin manager. */
class BottomBarSkinAssignActivity : BaseComposeActivity() {
    internal val model by viewModels<BottomBarAssignmentViewModel> {
        viewModelFactory { initializer { BottomBarAssignmentViewModel(AppBottomBarAssignmentRepository(), createSavedStateHandle(),
            intent.getStringExtra("sessionId").orEmpty(), intent.getStringExtra("editName"), intent.getStringExtra("name").orEmpty(), 56.dpToPx()) } }
    }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) { model.close() }
    }
    @Composable override fun Content(savedInstanceState: Bundle?) {
        BottomBarAssignmentRoute(model, { !isFinishing }, ::finish, { toastOnUi(it) })
    }
    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations && !model.state.value.finished) {
            lifecycleScope.launch(NonCancellable) { runCatching { model.releaseIfNeeded() } }
        }
        super.onDestroy()
    }
}
