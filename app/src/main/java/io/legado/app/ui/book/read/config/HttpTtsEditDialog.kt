package io.legado.app.ui.book.read.config

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppHttpTtsEditorRepository
import io.legado.app.data.repository.HttpTtsEditorField
import io.legado.app.model.ReadAloud
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

class HttpTtsEditDialog() : BaseComposeDialogFragment() {
    constructor(id: Long) : this() { arguments = Bundle().apply { putLong("id", id) } }
    private val viewModel by viewModels<HttpTtsEditViewModel> {
        viewModelFactory { initializer { HttpTtsEditViewModel(AppHttpTtsEditorRepository(), createSavedStateHandle(),
            arguments?.getLong("id")?.takeUnless { it == 0L }) } }
    }
    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) viewModel.codeResult(result.data?.getStringExtra("text"),
            result.data?.getIntExtra("cursorPosition", -1)) else viewModel.codeCancelled()
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); isCancelable = false }
    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    @Composable override fun Content() {
        HttpTtsEditRoute(viewModel, { isAdded && !childFragmentManager.isStateSaved }, ::handle,
            { dismissAllowingStateLoss() }, Modifier.fillMaxSize())
    }
    private fun handle(event: HttpTtsEditorEffect) {
        when (event.action) {
            HttpTtsEditorAction.Code -> {
                val field = event.field ?: HttpTtsEditorField.Name
                val title = httpTtsFieldLabel(field)?.let(::getString) ?: httpTtsLiteralLabel(field)
                editor.launch(Intent(requireContext(), CodeEditActivity::class.java).apply {
                    putExtra("text", event.text); putExtra("title", title); putExtra("cursorPosition", event.cursor)
                })
            }
            HttpTtsEditorAction.Saved -> toastOnUi("保存成功")
            HttpTtsEditorAction.Login -> startActivity<SourceLoginActivity> { putExtra("type", "httpTts"); putExtra("key", event.text) }
            HttpTtsEditorAction.Copy -> requireContext().sendToClip(event.text)
            HttpTtsEditorAction.Paste -> viewModel.paste(requireContext().getClipText())
            HttpTtsEditorAction.Log -> showDialogFragment(AppLogDialog())
            HttpTtsEditorAction.Help -> showHelp("httpTTSHelp")
            HttpTtsEditorAction.Rebuild -> ReadAloud.upReadAloudClass()
        }
    }
    override fun dismiss() { viewModel.requestExit() }
}
