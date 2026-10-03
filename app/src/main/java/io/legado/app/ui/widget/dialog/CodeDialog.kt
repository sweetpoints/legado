package io.legado.app.ui.widget.dialog

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AtomicCodeDialogRepository
import io.legado.app.data.repository.FileCodeDialogTransferRepository
import io.legado.app.help.IntentData
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.setLayout

internal fun resolveCodeDialogOriginal(
    showingAlternate: Boolean,
    originalCode: String,
    displayedCode: String,
): String = if (showingAlternate) originalCode else displayedCode

internal fun resolveCodeDialogPositionProgress(
    scrollY: Int,
    maxScrollY: Int,
    progressMax: Int = 10000,
): Int {
    if (maxScrollY <= 0 || progressMax <= 0) return 0
    return (scrollY.coerceIn(0, maxScrollY).toLong() * progressMax / maxScrollY).toInt()
}

class CodeDialog() : BaseComposeDialogFragment() {
    constructor(
        code: String,
        disableEdit: Boolean = true,
        requestId: String? = null,
        alternateCode: String? = null,
        showAlternate: Boolean = false,
        showReplaceRules: Boolean = false,
    ) : this() {
        arguments =
            Bundle().apply {
                putString("code", IntentData.put(code))
                putBoolean("disableEdit", disableEdit)
                putString("requestId", requestId)
                alternateCode?.let { putString("alternateCode", IntentData.put(it)) }
                putBoolean("showAlternate", showAlternate)
                putBoolean("showReplaceRules", showReplaceRules)
            }
    }

    internal val model by
        viewModels<CodeDialogViewModel> {
            viewModelFactory {
                initializer {
                    CodeDialogViewModel(
                        AtomicCodeDialogRepository(requireContext()),
                        createSavedStateHandle(),
                        IntentData.get<String>(arguments?.getString("code")).orEmpty(),
                        IntentData.get<String>(arguments?.getString("alternateCode")),
                        arguments?.getBoolean("disableEdit") != true,
                        arguments?.getBoolean("showReplaceRules") == true,
                        arguments?.getBoolean("showAlternate") == true,
                        transfer = FileCodeDialogTransferRepository(requireContext()),
                    )
                }
            }
        }
    val requestId: String?
        get() = arguments?.getString("requestId")

    private var overrideAlternate = false
    private var alternate: String? = null
    private var refreshPendingOverride: Boolean? = null
    private val editorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            model.editorReturned(
                result.resultCode == RESULT_OK,
                result.data?.getStringExtra("text"),
                result.data?.getStringExtra("textFile"),
                result.data?.getIntExtra("cursorPosition", 0) ?: 0,
            )
        }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.setOnKeyListener { _, key, event ->
            if (key != KeyEvent.KEYCODE_BACK) false
            else {
                if (event.action == KeyEvent.ACTION_UP && !model.state.value.busy) {
                    if (model.state.value.searchOpen) model.searchOpen(false) else model.close()
                }
                true
            }
        }
    }

    @Composable
    override fun Content() {
        val state by model.state.collectAsStateWithLifecycle()
        var manualEnabled by remember {
            mutableStateOf(callback()?.isManualSourceReplacementEnabled() == true)
        }
        LaunchedEffect(state.loaded) {
            if (state.loaded) {
                if (overrideAlternate) model.alternate(alternate)
                else if (model.sourcePreview)
                    model.alternate(callback()?.getCodeAlternate(requestId))
                model.refreshPending(
                    refreshPendingOverride ?: (callback()?.isReplaceRuleRefreshPending() == true)
                )
                manualEnabled = callback()?.isManualSourceReplacementEnabled() == true
            }
        }
        LaunchedEffect(state.refreshPending) {
            manualEnabled = callback()?.isManualSourceReplacementEnabled() == true
        }
        SideEffect { isCancelable = !state.busy }
        CodeDialogRoute(
            model,
            manualEnabled,
            { isAdded && !parentFragmentManager.isStateSaved },
            ::handle,
            ::dismissAllowingStateLoss,
        )
    }

    private fun handle(effect: CodeDialogEffect) {
        when (effect.action) {
            CodeDialogAction.Save -> {
                val code = currentOriginalCode()
                model.close()
                callback()?.onCodeSave(code, requestId)
            }
            CodeDialogAction.EditorSaved -> callback()?.onCodeSave(currentOriginalCode(), requestId)
            CodeDialogAction.ReplaceRules -> callback()?.onOpenReplaceRules()
            CodeDialogAction.Effective,
            CodeDialogAction.Manual ->
                callback()
                    ?.onShowSourceReplacements(
                        currentOriginalCode(),
                        requestId,
                        effect.action == CodeDialogAction.Manual,
                    )
            CodeDialogAction.Editor -> {
                try {
                    val state = model.state.value
                    editorLauncher.launch(
                        Intent(requireContext(), CodeEditActivity::class.java).apply {
                            putExtra("useTextFile", true)
                            putExtra("textFile", state.editorPath)
                            putExtra("readOnly", state.editorReadOnly)
                            putExtra(
                                "cursorPosition",
                                if (state.showingAlternate && model.sourcePreview) 0
                                else state.selectionStart,
                            )
                        }
                    )
                } catch (error: Exception) {
                    model.editorReturned(false, null, null, 0)
                }
            }
        }
    }

    fun refreshAlternateCode() {
        overrideAlternate = true
        alternate = callback()?.getCodeAlternate(requestId)
        model.alternate(alternate)
    }

    fun clearAlternateCode() {
        overrideAlternate = true
        alternate = null
        model.alternate(null)
    }

    fun setReplaceRuleRefreshPending(pending: Boolean) {
        refreshPendingOverride = pending
        model.refreshPending(pending)
    }

    fun currentOriginalCode(): String = model.state.value.original

    private fun callback(): Callback? = (parentFragment as? Callback) ?: activity as? Callback

    interface Callback {
        fun onCodeSave(code: String, requestId: String?)

        fun onOpenReplaceRules() = Unit

        fun onShowSourceReplacements(code: String, requestId: String?, manual: Boolean) = Unit

        fun isManualSourceReplacementEnabled(): Boolean = false

        fun getCodeAlternate(requestId: String?): String? = null

        fun isReplaceRuleRefreshPending(): Boolean = false
    }
}
