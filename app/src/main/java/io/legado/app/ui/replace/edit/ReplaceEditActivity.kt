package io.legado.app.ui.replace.edit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.*
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.keyboard.KeyboardAssistsConfig
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

class ReplaceEditActivity : BaseComposeActivity() {
    val viewModel by viewModels<ReplaceEditorViewModel> {
        // Intent may carry the reader's selected text. Do not copy it into SavedState defaults.
        object : AbstractSavedStateViewModelFactory(this, null) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, state: SavedStateHandle): T =
                ReplaceEditorViewModel(RoomReplaceEditorRepository(applicationContext), state,
                    ReplaceEditorRequest(intent.getLongExtra("id", -1), intent.getStringExtra("pattern").orEmpty(),
                        intent.getBooleanExtra("isRegex", false), intent.getStringExtra("scope"))) as T
        }
    }
    private val assist by lazy { ReplaceEditorAssistRepository() }
    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val nonce = viewModel.state.value.editor?.nonce ?: return@registerForActivityResult
        val cursor = result.data?.takeIf { it.hasExtra("cursorPosition") }?.getIntExtra("cursorPosition", -1)
        viewModel.editorResult(nonce, result.data?.getStringExtra("text"), result.data?.getStringExtra("textFile"), cursor, result.resultCode == RESULT_OK)
    }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) { finish() }
    }
    @Composable override fun Content(savedInstanceState: Bundle?) {
        ReplaceEditorRoute(viewModel, assist, { !supportFragmentManager.isStateSaved && !isFinishing },
            { launch, cursor -> editor.launch(Intent(this, CodeEditActivity::class.java).apply {
                putExtra("useTextFile", true); putExtra("textFile", launch.path)
                putExtra("title", getString(launch.field.label())); putExtra("cursorPosition", cursor)
            }) }, { saved -> if (saved) setResult(RESULT_OK); super.finish() },
            { sendToClip(it) }, { getClipText() }, { showHelp("regexHelp") },
            { showDialogFragment<KeyboardAssistsConfig>() }, { toastOnUi(it) })
    }
    override fun onStop() {
        lifecycleScope.launch(NonCancellable) { runCatching { viewModel.flushDraft() } }
        super.onStop()
    }
    override fun finish() { viewModel.close() }
    companion object {
        fun startIntent(context: Context, id: Long = -1, pattern: String? = null,
            isRegex: Boolean = false, scope: String? = null): Intent = Intent(context, ReplaceEditActivity::class.java).apply {
            putExtra("id", id); putExtra("pattern", pattern); putExtra("isRegex", isRegex); putExtra("scope", scope)
        }
    }
}
