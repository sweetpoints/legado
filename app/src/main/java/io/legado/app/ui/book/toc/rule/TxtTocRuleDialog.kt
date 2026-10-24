package io.legado.app.ui.book.toc.rule

import io.legado.app.utils.resizeForIme

import android.os.Bundle
import kotlinx.coroutines.launch
import android.view.ViewGroup
import android.view.View
import android.view.LayoutInflater
import android.view.KeyEvent
import android.widget.FrameLayout
import android.graphics.Color
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.launchCleanup

import androidx.fragment.app.viewModels

import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.repository.RoomTxtTocRuleManagementRepository
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp

/** Reader rule picker. Data mutations share the management repository; selection stays local. */
class TxtTocRuleDialog() : BaseDialogFragment(0), TxtTocRuleEditDialog.Callback {
    constructor(tocRegex: String?) : this() {
        arguments = Bundle().apply { putString("txtTocSeed", TxtTocPickerSeed.create(tocRegex)) }
    }

    val viewModel by
        viewModels<TxtTocRuleManagementViewModel> {
            viewModelFactory {
                initializer {
                    TxtTocRuleManagementViewModel(
                        RoomTxtTocRuleManagementRepository(requireContext().applicationContext),
                        createSavedStateHandle().apply { keys().filterNot { it == "txt.toc.management.ticket" }.forEach { remove<Any?>(it) } },
                        picker = true,
                        initialRegexTicket = arguments?.getString("txtTocSeed"),
                        sessions = FileTxtTocManagementSessionRepository(requireContext()),
                    )
                }
            }
        }
    private var requestedName: String? = null
    var selectedName: String?
        get() =
            viewModel.state.value.rules
                .firstOrNull { it.id == viewModel.state.value.selectedId }
                ?.name ?: requestedName
        set(value) {
            requestedName = value
            if (isAdded) applyRequestedName()
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
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.setBackgroundDrawableResource(R.color.transparent)
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) requestClose(); true } else false
        }
        dialog?.window?.resizeForIme()
    }

    private val managerFragment get() = childFragmentManager.findFragmentByTag("txt-toc-picker-flutter") as? TxtTocManagerFlutterFragment
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setStyle(STYLE_NO_TITLE, 0); isCancelable = false }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = FrameLayout(requireContext()).apply { id = R.id.txt_toc_manager_flutter_content }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(Color.TRANSPARENT)
        coordinator
        lifecycleScope.launch {
            viewModel.state.collect { if (!it.loading) applyRequestedName() }
        }
        if (managerFragment == null) childFragmentManager.beginTransaction().add(R.id.txt_toc_manager_flutter_content, TxtTocManagerFlutterFragment(), "txt-toc-picker-flutter").commitNow()
    }
    private fun applyRequestedName() {
        if (viewModel.state.value.loading) return
        requestedName?.let { name -> viewModel.state.value.rules.firstOrNull { it.name == name }?.let { viewModel.choose(it.id) }; requestedName = null }
    }
    internal fun nativeAction(op: String, id: Long?) {
        when(op) {
            "add" -> showDialogFragment(TxtTocRuleEditDialog())
            "edit" -> showDialogFragment(TxtTocRuleEditDialog(checkNotNull(id)))
            "importFile" -> importDoc.launch { mode = HandleFileContract.FILE; allowExtensions = arrayOf("txt", "json") }
            "importQr" -> qrCodeResult.launch(null)
            "help" -> showHelp("txtTocRuleHelp")
        }
    }
    internal fun closeManagement() { if (!viewModel.state.value.busy) dismissAllowingStateLoss() }
    private fun requestClose() {
        managerFragment?.takeIf { it.flutterEngine?.dartExecutor?.isExecutingDart == true }?.onBackPressed() ?: closeManagement()
    }
    override fun dismiss() { requestClose() }
    override fun onResume() { super.onResume(); view?.post { if (isResumed) { managerFragment?.onPostResume(); coordinator.onHostReady() } } }
    internal val coordinator by lazy {
        TxtTocManagerNativeCoordinator(viewModel, lifecycle, lifecycleScope,
            { isAdded && !parentFragmentManager.isStateSaved && !childFragmentManager.isStateSaved }, true,
            { effect -> when(effect.kind) {
                TxtTocManagementEffectKind.ImportText -> showDialogFragment(ImportTxtTocRuleDialog(effect.value))
                TxtTocManagementEffectKind.ReturnRegex -> (activity as? CallBack)?.onTocRegexDialogResult(effect.value)
                else -> Unit
            } }, { toastOnUi("ERROR") }, ::closeManagement)
    }
    override fun onDestroy() {
        coordinator.close()
        if (activity?.isChangingConfigurations != true && (isRemoving || activity?.isFinishing == true)) {
            lifecycleScope.launchCleanup { viewModel.closeSession() }
            arguments?.getString("txtTocSeed")?.let { ticket -> lifecycleScope.launchCleanup { TxtTocPickerSeed.release(ticket) } }
        }
        super.onDestroy()
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
