package io.legado.app.ui.dict.rule

import io.legado.app.utils.resizeForIme

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.repository.RoomDictionaryRuleRepository
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi

class DictRuleEditDialog() : BaseDialogFragment(0) {
    constructor(name: String) : this() {
        arguments = Bundle().apply { putString("name", name) }
    }

    val viewModel by
        viewModels<DictionaryRuleEditViewModel> {
            viewModelFactory {
                initializer {
                    DictionaryRuleEditViewModel(
                        RoomDictionaryRuleRepository(),
                        createSavedStateHandle(),
                        arguments?.getString("name"),
                    )
                }
            }
        }
    private val textEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                if (
                    !viewModel.fullEditResult(
                        result.data?.getStringExtra("text"),
                        result.data?.getIntExtra("cursorPosition", 0),
                    )
                )
                    toastOnUi(R.string.focus_lost_on_textbox)
            } else viewModel.fullEditCancelled()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
        isCancelable = false
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            resizeForIme()
        }
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) coordinator.requestClose()
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        view?.setBackgroundColor(Color.TRANSPARENT)
        view?.post { if (isResumed) (childFragmentManager.findFragmentByTag("dictionary-editor-flutter") as? DictionaryRuleEditFlutterFragment)?.onPostResume() }
    }

    override fun dismiss() {
        coordinator.requestClose()
    }

    internal val coordinator by lazy {
        DictionaryRuleEditNativeCoordinator(viewModel, lifecycle, lifecycleScope,
            { isAdded && !parentFragmentManager.isStateSaved },
            { request ->
                val title = when (request.field) {
                    DictionaryRuleField.Name -> R.string.name
                    DictionaryRuleField.UrlRule -> R.string.url_rule
                    DictionaryRuleField.ShowRule -> R.string.show_rule
                }
                textEditLauncher.launch(Intent(requireActivity(), CodeEditActivity::class.java).apply {
                    putExtra("text", request.text)
                    putExtra("title", getString(title))
                    putExtra("cursorPosition", request.cursor)
                })
            }, { requireContext().sendToClip(it) }, { requireContext().getClipText() },
            { toastOnUi(R.string.please_focus_cursor_on_textbox) }, ::dismissAllowingStateLoss)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = R.id.dictionary_rule_editor_flutter_content }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(Color.TRANSPARENT)
        coordinator
        if (childFragmentManager.findFragmentByTag("dictionary-editor-flutter") == null)
            childFragmentManager.beginTransaction().add(R.id.dictionary_rule_editor_flutter_content,
                DictionaryRuleEditFlutterFragment(), "dictionary-editor-flutter").commitNow()
    }

    override fun onDestroy() { coordinator.close(); super.onDestroy() }
}
