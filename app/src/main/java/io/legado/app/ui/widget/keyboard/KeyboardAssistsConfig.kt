package io.legado.app.ui.widget.keyboard

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.data.repository.KeyboardAssistSettingsText
import io.legado.app.data.repository.RoomKeyboardAssistSettingsRepository
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

class KeyboardAssistsConfig : BaseComposeDialogFragment(), CodeDialog.Callback {
    internal val model by viewModels<KeyboardAssistSettingsViewModel> {
        viewModelFactory { initializer { KeyboardAssistSettingsViewModel(RoomKeyboardAssistSettingsRepository(requireContext()), createSavedStateHandle()) } }
    }
    override fun onStart() { super.onStart(); setLayout(0.9f, 0.9f) }
    @Composable override fun Content() {
        KeyboardAssistSettingsRoute(model, { isAdded && !parentFragmentManager.isStateSaved },
            ::dismiss, { postEvent(PreferKey.showBoardLine, it) }, ::editCode, { isCancelable = it })
    }
    private fun editCode(key: Boolean) {
        val session = model.editorSession ?: return
        val draft = model.state.value.editor ?: return
        if (model.state.value.busy) return
        val text = if (key) draft.key.text else draft.value.text
        CodeDialog(text, false, "$session:${if (key) "key" else "value"}").show(childFragmentManager, "keyboard-assist-code")
    }
    override fun onCodeSave(code: String, requestId: String?) {
        val session = model.editorSession ?: return
        val key = when (requestId) { "$session:key" -> true; "$session:value" -> false; else -> return }
        val draft = model.state.value.editor ?: return
        val current = if (key) draft.key else draft.value
        if (current.text == code) return
        model.editorText(key, KeyboardAssistSettingsText(code, current.start, current.end).bounded())
    }
    override fun onStop() {
        lifecycleScope.launch(NonCancellable) { runCatching { model.flushDraft() } }
        super.onStop()
    }
}
