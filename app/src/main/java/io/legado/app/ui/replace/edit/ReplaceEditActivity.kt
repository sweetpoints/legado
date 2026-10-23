package io.legado.app.ui.replace.edit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.widget.FrameLayout
import io.legado.app.R
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import io.legado.app.ui.association.compose.associationCreationExtras
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseThemedActivity
import io.legado.app.data.repository.*
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.keyboard.KeyboardAssistsConfig
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.launchCleanup

class ReplaceEditActivity : BaseThemedActivity() {
    val viewModel by
        viewModels<ReplaceEditorViewModel> {
            // Intent may carry the reader's selected text. Do not copy it into SavedState defaults.
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras,
                ): T =
                    ReplaceEditorViewModel(
                        RoomReplaceEditorRepository(applicationContext),
                        associationCreationExtras(extras).createSavedStateHandle(),
                        ReplaceEditorRequest(
                            intent.getLongExtra("id", -1),
                            intent.getStringExtra("pattern").orEmpty(),
                            intent.getBooleanExtra("isRegex", false),
                            intent.getStringExtra("scope"),
                        ),
                    )
                        as T
            }
        }
    private val assist by lazy { ReplaceEditorAssistRepository() }
    private val editor =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val nonce = viewModel.state.value.editor?.nonce ?: return@registerForActivityResult
            val cursor =
                result.data
                    ?.takeIf { it.hasExtra("cursorPosition") }
                    ?.getIntExtra("cursorPosition", -1)
            viewModel.editorResult(
                nonce,
                result.data?.getStringExtra("text"),
                result.data?.getStringExtra("textFile"),
                cursor,
                result.resultCode == RESULT_OK,
            )
        }

    internal val editorCoordinator by lazy {
        ReplaceEditorNativeCoordinator(this, viewModel, assist, lifecycle, lifecycleScope,
            { !supportFragmentManager.isStateSaved && !isFinishing && !isDestroyed },
            { launch, cursor ->
                editor.launch(Intent(this, CodeEditActivity::class.java).apply {
                    putExtra("useTextFile", true)
                    putExtra("textFile", launch.path)
                    putExtra("title", getString(launch.field.label()))
                    putExtra("cursorPosition", cursor)
                })
            }, { saved -> if (saved) setResult(RESULT_OK); super.finish() },
            { sendToClip(it) }, { getClipText() },
            { showHelp("regexHelp") }, { showDialogFragment<KeyboardAssistsConfig>() },
            { toastOnUi(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) { editorCoordinator.requestBack() }
        super.onCreate(savedInstanceState)
    }
    override fun createContentView() {
        setContentView(FrameLayout(this).apply { id = R.id.replace_editor_flutter_container })
    }
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        editorCoordinator
        if (editorFragment == null) supportFragmentManager.beginTransaction()
            .add(R.id.replace_editor_flutter_container, ReplaceEditorFlutterFragment(), FRAGMENT_TAG)
            .commitNow()
    }
    private val editorFragment get() = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? ReplaceEditorFlutterFragment
    override fun onPostResume() { super.onPostResume(); editorFragment?.onPostResume(); editorCoordinator.onHostReady() }
    override fun onNewIntent(intent: Intent) { editorFragment?.onNewIntent(intent); super.onNewIntent(intent) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        editorFragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        editorFragment?.onActivityResult(requestCode, resultCode, data)
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); editorFragment?.onUserLeaveHint() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); editorFragment?.onTrimMemory(level) }
    override fun onDestroy() { editorCoordinator.close(); super.onDestroy() }

    override fun onStop() {
        lifecycleScope.launchCleanup { runCatching { viewModel.flushDraft() } }
        super.onStop()
    }

    override fun finish() {
        editorCoordinator.requestBack()
    }

    companion object {
        private const val FRAGMENT_TAG = "replace-editor-flutter"
        fun startIntent(
            context: Context,
            id: Long = -1,
            pattern: String? = null,
            isRegex: Boolean = false,
            scope: String? = null,
        ): Intent =
            Intent(context, ReplaceEditActivity::class.java).apply {
                putExtra("id", id)
                putExtra("pattern", pattern)
                putExtra("isRegex", isRegex)
                putExtra("scope", scope)
            }
    }
}
