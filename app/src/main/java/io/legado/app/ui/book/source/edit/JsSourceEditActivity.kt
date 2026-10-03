package io.legado.app.ui.book.source.edit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.toastOnUi

/** Platform launchers only; executable source drafts are never saved in Activity state. */
class JsSourceEditActivity : BaseComposeActivity(imageBg = false) {
    private val model by
        viewModels<JsSourceEditViewModel> {
            viewModelFactory {
                initializer {
                    JsSourceEditViewModel(
                        repository = FileJsSourceEditRepository(applicationContext),
                        savedState = createSavedStateHandle(),
                        initialSourceUrl = intent.getStringExtra("sourceUrl"),
                    )
                }
            }
        }
    private val debugResult =
        registerForActivityResult(StartActivityContract(BookSourceDebugActivity::class.java)) {
            model.returned(JsSourceEditStage.DEBUG_OPEN)
        }
    private val editorResult =
        registerForActivityResult(StartActivityContract(CodeEditActivity::class.java)) { result ->
            model.editorReturned(
                ok = result.resultCode == Activity.RESULT_OK,
                text = result.data?.getStringExtra("text"),
                path = result.data?.getStringExtra("textFile"),
                action = result.data?.getStringExtra(CodeEditActivity.EXTRA_RESULT_ACTION),
            )
        }
    private val loginResult =
        registerForActivityResult(StartActivityContract(SourceLoginActivity::class.java)) {
            model.returned(JsSourceEditStage.LOGIN_OPEN)
        }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        JsSourceEditRoute(model, ::launch, ::complete, canHandle = { !isFinishing && !isDestroyed })
    }

    private fun launch(stage: JsSourceEditStage, state: JsSourceEditState) {
        when (stage) {
            JsSourceEditStage.EDITOR_OPEN -> launchEditor(state)
            JsSourceEditStage.DEBUG_OPEN -> debugResult.launch { putExtra("key", state.sourceUrl) }
            JsSourceEditStage.LOGIN_OPEN ->
                loginResult.launch {
                    putExtra("type", "bookSource")
                    putExtra("key", state.sourceUrl)
                }
            else -> Unit
        }
    }

    private fun launchEditor(state: JsSourceEditState) {
        editorResult.launch {
            putExtra("textFile", state.editorPath)
            putExtra("useTextFile", true)
            putExtra("title", getString(R.string.js_source_edit))
            putExtra("languageName", "source.js")
            putExtra("returnUnchangedText", true)
            putExtra(CodeEditActivity.EXTRA_CHECK_JAVASCRIPT_SYNTAX, true)
            putExtra(CodeEditActivity.EXTRA_SHOW_DEBUG_SOURCE, true)
            putExtra(CodeEditActivity.EXTRA_SHOW_LOGIN_SOURCE, true)
        }
        if (state.missingLogin) toastOnUi(R.string.source_no_login)
    }

    private fun complete(state: JsSourceEditState) {
        if (state.saved) {
            setResult(Activity.RESULT_OK, Intent().putExtra("origin", state.sourceUrl))
            if (state.successToast) toastOnUi(R.string.success)
        }
        super.finish()
    }
}
