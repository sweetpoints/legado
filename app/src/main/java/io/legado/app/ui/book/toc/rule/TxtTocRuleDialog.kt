package io.legado.app.ui.book.toc.rule

import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.repository.RoomTxtTocRuleManagementRepository
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp

/** Reader rule picker. Data mutations share the management repository; selection stays local. */
class TxtTocRuleDialog() : BaseComposeDialogFragment(), TxtTocRuleEditDialog.Callback {
    constructor(tocRegex: String?) : this() {
        arguments = Bundle().apply { putString("tocRegex", tocRegex) }
    }

    private val viewModel by
        viewModels<TxtTocRuleManagementViewModel> {
            viewModelFactory {
                initializer {
                    TxtTocRuleManagementViewModel(
                        RoomTxtTocRuleManagementRepository(requireContext().applicationContext),
                        createSavedStateHandle(),
                        picker = true,
                        initialRegex = arguments?.getString("tocRegex"),
                    )
                }
            }
        }
    private var requestedName by mutableStateOf<String?>(null)
    var selectedName: String?
        get() =
            viewModel.state.value.rules
                .firstOrNull { it.id == viewModel.state.value.selectedId }
                ?.name ?: requestedName
        set(value) {
            requestedName = value
        }

    private val qrCodeResult =
        registerForActivityResult(QrCodeResult()) { value ->
            value?.let { showDialogFragment(ImportTxtTocRuleDialog(it)) }
        }
    private val importDoc =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { showDialogFragment(ImportTxtTocRuleDialog(it.toString())) }
        }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.rules, state.loading, requestedName) {
            if (!state.loading)
                requestedName?.let { name ->
                    state.rules.firstOrNull { it.name == name }?.let { viewModel.choose(it.id) }
                    requestedName = null
                }
        }
        TxtTocRuleManagementRoute(
            viewModel,
            ::dismissAllowingStateLoss,
            { showDialogFragment(TxtTocRuleEditDialog()) },
            { showDialogFragment(TxtTocRuleEditDialog(it)) },
            {
                importDoc.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            },
            { qrCodeResult.launch(null) },
            { showHelp("txtTocRuleHelp") },
            { effect ->
                when (effect.kind) {
                    TxtTocManagementEffectKind.ImportText ->
                        showDialogFragment(ImportTxtTocRuleDialog(effect.value))
                    TxtTocManagementEffectKind.ReturnRegex ->
                        (activity as? CallBack)?.onTocRegexDialogResult(effect.value)
                    else -> Unit
                }
            },
            picker = true,
        )
    }

    override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
        // The editor has already persisted successfully; observeAll refreshes this host.
    }

    override fun onPause() {
        viewModel.cancelGestures()
        super.onPause()
    }

    interface CallBack {
        fun onTocRegexDialogResult(tocRegex: String) {}
    }
}
