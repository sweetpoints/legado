package io.legado.app.ui.autoTask

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.RoomAutoTaskEditorRepository
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

class AutoTaskEditActivity : BaseComposeActivity() {
    val viewModel by viewModels<AutoTaskEditorViewModel> {
        viewModelFactory { initializer { AutoTaskEditorViewModel(RoomAutoTaskEditorRepository(applicationContext),
            createSavedStateHandle(), intent.getStringExtra(EXTRA_ID)) } }
    }
    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.editorReturned(result.resultCode == RESULT_OK, result.data?.getStringExtra("text"),
            result.data?.getStringExtra("textFile"), result.data?.getIntExtra("cursorPosition", 0) ?: 0)
    }
    @Composable override fun Content(savedInstanceState: Bundle?) {
        AutoTaskEditorRoute(viewModel, ::deliver, ::close, { toastOnUi(it) }, { !supportFragmentManager.isStateSaved })
    }
    private fun close(saved: Boolean) { if (saved) setResult(RESULT_OK); super.finish() }
    private fun deliver(effect: AutoTaskEditorEffect, payload: String?) {
        when (effect.kind) {
            AutoTaskEditorEffectKind.SavedDebug -> { setResult(RESULT_OK); startActivity(AutoTaskDebugActivity.intent(this, viewModel.taskId)) }
            AutoTaskEditorEffectKind.SavedLogin -> {
                setResult(RESULT_OK); startActivity<SourceLoginActivity> { putExtra("type", "autoTask"); putExtra("key", viewModel.taskId) }
            }
            AutoTaskEditorEffectKind.SavedOnly -> setResult(RESULT_OK)
            AutoTaskEditorEffectKind.Clipboard -> sendToClip(requireNotNull(payload))
            AutoTaskEditorEffectKind.Paste -> viewModel.paste(getClipText())
            AutoTaskEditorEffectKind.Help -> showHelp("autoTaskHelp")
            AutoTaskEditorEffectKind.Editor -> editor.launch(Intent(this, CodeEditActivity::class.java).apply {
                putExtra("textFile", effect.path); putExtra("useTextFile", true)
                putExtra("title", getString(requireNotNull(effect.field).label())); putExtra("cursorPosition", effect.cursor)
                putExtra("returnUnchangedText", true)
            })
            AutoTaskEditorEffectKind.Close, AutoTaskEditorEffectKind.SavedClose -> Unit
        }
    }
    override fun finish() { viewModel.requestExit() }
    companion object {
        private const val EXTRA_ID = "autoTaskId"
        fun intent(context: Context, id: String) = Intent(context, AutoTaskEditActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
