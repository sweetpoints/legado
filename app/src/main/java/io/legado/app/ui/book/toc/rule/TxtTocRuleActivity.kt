package io.legado.app.ui.book.toc.rule

import android.os.Bundle
import androidx.activity.viewModels
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.launchCleanup
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseThemedActivity
import io.legado.app.data.repository.RoomTxtTocRuleManagementRepository
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import java.io.File

class TxtTocRuleActivity : BaseThemedActivity(), TxtTocRuleEditDialog.Callback {
    val viewModel by
        viewModels<TxtTocRuleManagementViewModel> {
            viewModelFactory {
                initializer {
                    TxtTocRuleManagementViewModel(
                        RoomTxtTocRuleManagementRepository(applicationContext),
                        createSavedStateHandle().apply { keys().filterNot { it == "txt.toc.management.ticket" }.forEach { remove<Any?>(it) } },
                        sessions = FileTxtTocManagementSessionRepository(applicationContext),
                    )
                }
            }
        }
    private val qrCodeResult =
        registerForActivityResult(QrCodeResult()) { value ->
            value?.let { showDialogFragment(ImportTxtTocRuleDialog(it)) }
        }
    private val importDoc =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { showDialogFragment(ImportTxtTocRuleDialog(it.toString())) }
        }
    private val exportResult =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { viewModel.exportFinished(it.toString()) }
        }

    private val managerFragment get() = supportFragmentManager.findFragmentByTag("txt-toc-manager-flutter") as? TxtTocManagerFlutterFragment
    override fun createContentView() { setContentView(FrameLayout(this).apply { id = R.id.txt_toc_manager_flutter_content }) }
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        coordinator
        onBackPressedDispatcher.addCallback(this) {
            managerFragment?.takeIf { it.flutterEngine?.dartExecutor?.isExecutingDart == true }?.onBackPressed() ?: closeManagement()
        }
        if (managerFragment == null) supportFragmentManager.beginTransaction().add(R.id.txt_toc_manager_flutter_content, TxtTocManagerFlutterFragment(), "txt-toc-manager-flutter").commitNow()
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
    internal fun closeManagement() { if (!viewModel.state.value.busy) finish() }
    internal val coordinator by lazy {
        TxtTocManagerNativeCoordinator(viewModel, lifecycle, lifecycleScope,
            { !isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved }, false,
            ::deliver, { toastOnUi("ERROR") }, ::closeManagement)
    }
    override fun onPostResume() { super.onPostResume(); managerFragment?.onPostResume(); coordinator.onHostReady() }
    override fun onDestroy() { coordinator.close(); if (isFinishing) lifecycleScope.launchCleanup { viewModel.closeSession() }; super.onDestroy() }

    private fun deliver(effect: TxtTocManagementEffect) {
        when (effect.kind) {
            TxtTocManagementEffectKind.ShareFile -> share(File(effect.value))
            TxtTocManagementEffectKind.ExportJson ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData =
                        HandleFileContract.FileData(
                            "exportTxtTocRule.json",
                            effect.value,
                            "application/json",
                        )
                }
            TxtTocManagementEffectKind.ImportText ->
                showDialogFragment(ImportTxtTocRuleDialog(effect.value))
            TxtTocManagementEffectKind.Clipboard -> sendToClip(effect.value)
            TxtTocManagementEffectKind.ReturnRegex -> Unit
        }
    }

    override fun saveTxtTocRule(txtTocRule: io.legado.app.data.entities.TxtTocRule) {
        // The editor has already persisted successfully; observeAll refreshes this host.
    }

    override fun onPause() {
        viewModel.cancelGestures()
        super.onPause()
    }
}
