package io.legado.app.ui.book.toc.rule

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.repository.RoomTxtTocRuleEditorRepository
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.*

class TxtTocRuleEditDialog() : BaseComposeDialogFragment() {
    constructor(id: Long?) : this() {
        if (id != null) arguments = Bundle().apply { putLong("id", id) }
    }

    private val model by
        viewModels<TxtTocRuleEditorViewModel> {
            viewModelFactory {
                initializer {
                    TxtTocRuleEditorViewModel(
                        RoomTxtTocRuleEditorRepository(),
                        createSavedStateHandle(),
                        arguments?.takeIf { it.containsKey("id") }?.getLong("id"),
                    )
                }
            }
        }
    private val callback
        get() = txtTocRuleEditorCallback(parentFragment, activity)

    private val codeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                if (
                    !model.codeResult(
                        result.data?.getStringExtra("text"),
                        result.data?.getIntExtra("cursorPosition", 0),
                    )
                )
                    toastOnUi(R.string.focus_lost_on_textbox)
            } else model.codeCancelled()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog?.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) model.requestClose()
                true
            } else false
        }
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        view?.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onResume() {
        super.onResume()
        view?.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun dismiss() {
        model.requestClose()
    }

    @Composable
    override fun Content() {
        TxtTocRuleEditorRoute(
            model,
            { request ->
                val title =
                    when (request.field) {
                        TxtTocEditorField.Name -> R.string.name
                        TxtTocEditorField.Regex -> R.string.regex
                        TxtTocEditorField.Replacement -> R.string.replace_to_js
                        TxtTocEditorField.Example -> R.string.example
                    }
                codeLauncher.launch(
                    Intent(requireContext(), CodeEditActivity::class.java).apply {
                        putExtra("text", request.text)
                        putExtra("title", getString(title))
                        putExtra("cursorPosition", request.cursor)
                    }
                )
            },
            { requireContext().sendToClip(it) },
            { requireContext().getClipText() },
            { toastOnUi(R.string.please_focus_cursor_on_textbox) },
            { callback?.saveTxtTocRule(it.entity()) },
            ::dismissAllowingStateLoss,
        )
    }

    interface Callback {
        fun saveTxtTocRule(txtTocRule: TxtTocRule)
    }
}

internal fun txtTocRuleEditorCallback(
    parent: Any?,
    activity: Any?,
): TxtTocRuleEditDialog.Callback? =
    (parent as? TxtTocRuleEditDialog.Callback) ?: activity as? TxtTocRuleEditDialog.Callback
