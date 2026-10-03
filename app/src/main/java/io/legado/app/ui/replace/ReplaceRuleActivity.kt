package io.legado.app.ui.replace

import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.AppReplaceManagementRepository
import io.legado.app.data.repository.FileReplaceManagementSessionRepository
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import java.io.File

/** Pure Compose management; imports and replacements keep their established business pipelines. */
class ReplaceRuleActivity : BaseComposeActivity() {
    private val repository by lazy { AppReplaceManagementRepository() }
    internal val managementModel by
        viewModels<ReplaceManagementViewModel> {
            viewModelFactory {
                initializer {
                    val saved =
                        createSavedStateHandle().apply {
                            keys()
                                .filterNot { it.startsWith("replaceManagement.") }
                                .forEach { remove<Any?>(it) }
                        }
                    ReplaceManagementViewModel(
                        repository,
                        FileReplaceManagementSessionRepository(),
                        saved,
                    )
                }
            }
        }
    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                managementModel.changed()
                setResult(RESULT_OK)
            }
        }
    private val importDoc =
        registerForActivityResult(HandleFileContract()) { result ->
            (result.value ?: managementModel.waiting(ReplaceManagementAction.ImportLocal))?.let {
                managementModel.returned(it, result.uri?.toString())
            }
        }
    private val exportResult =
        registerForActivityResult(HandleFileContract()) { result ->
            (result.value ?: managementModel.waiting(ReplaceManagementAction.Export))?.let {
                managementModel.returned(it, result.uri?.toString())
            }
        }
    private var qrLauncher: ActivityResultLauncher<Unit?>? = null
    private var qrNonce: String? = null

    private fun registerQr(nonce: String): ActivityResultLauncher<Unit?> {
        if (qrNonce == nonce) return checkNotNull(qrLauncher)
        qrLauncher?.unregister()
        qrNonce = nonce
        return activityResultRegistry
            .register("replace-management-qr-$nonce", QrCodeResult()) { result ->
                managementModel.returned(nonce, result)
            }
            .also { qrLauncher = it }
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        managementModel.bind(
            ReplaceManagementLabels(
                getString(R.string.enabled),
                getString(R.string.disabled),
                getString(R.string.no_group),
            )
        )
        managementModel.waiting(ReplaceManagementAction.ImportQr)?.let(::registerQr)
        onBackPressedDispatcher.addCallback(this) { close() }
    }

    private fun close() {
        if (managementModel.state.value.busy) return
        if (managementModel.state.value.changed) setResult(RESULT_OK)
        managementModel.cancelGesture()
        finish()
    }

    private fun ready() = !isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        val state by managementModel.state.collectAsState()
        LaunchedEffect(state.changed) { if (state.changed) setResult(RESULT_OK) }
        ReplaceManagementRoute(managementModel, ::ready, ::close, ::native)
    }

    private fun native(request: ReplaceManagementNative, preparedImport: String?) {
        try {
            when (request.effect.action) {
                ReplaceManagementAction.Add ->
                    editActivity.launch(ReplaceEditActivity.startIntent(this))
                ReplaceManagementAction.Edit ->
                    request.ruleId?.let {
                        editActivity.launch(ReplaceEditActivity.startIntent(this, it))
                    }
                ReplaceManagementAction.ImportLocal ->
                    importDoc.launch {
                        mode = HandleFileContract.FILE
                        allowExtensions = arrayOf("txt", "json")
                        value = request.effect.nonce
                    }
                ReplaceManagementAction.ImportQr -> registerQr(request.effect.nonce).launch(null)
                ReplaceManagementAction.ImportUrl,
                ReplaceManagementAction.ImportInput ->
                    checkNotNull(preparedImport).let {
                        showDialogFragment(ImportReplaceRuleDialog.prepared(it))
                    }
                ReplaceManagementAction.Export ->
                    request.export?.let { export ->
                        exportResult.launch {
                            mode = HandleFileContract.EXPORT
                            value = request.effect.nonce
                            fileData =
                                HandleFileContract.FileData(
                                    export.name,
                                    File(export.path),
                                    "application/json",
                                )
                        }
                    }
                ReplaceManagementAction.Share -> request.export?.let { share(File(it.path)) }
                ReplaceManagementAction.Help -> showHelp("replaceRuleHelp")
                ReplaceManagementAction.Groups -> showDialogFragment<GroupManageDialog>()
                ReplaceManagementAction.Copy -> request.input?.let { sendToClip(it) }
            }
        } catch (error: Exception) {
            managementModel.returned(request.effect.nonce, null)
            toastOnUi(error.localizedMessage ?: error.javaClass.simpleName)
            throw error // Route releases a prepared import which was never handed to its dialog
            // owner.
        }
    }

    override fun onDestroy() {
        qrLauncher?.unregister()
        qrLauncher = null
        Coroutine.async { repository.refreshPipeline() }
        super.onDestroy()
    }
}
