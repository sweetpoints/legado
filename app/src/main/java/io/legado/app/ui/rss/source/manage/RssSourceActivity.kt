package io.legado.app.ui.rss.source.manage

import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.*
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import java.io.File

/**
 * Direct Compose source management. Parsing, imports and source cleanup retain their established
 * pipelines.
 */
class RssSourceActivity : BaseComposeActivity() {
    internal val managementModel by
        viewModels<RssSourceManagementViewModel> {
            viewModelFactory {
                initializer {
                    val handle =
                        createSavedStateHandle().apply {
                            keys()
                                .filterNot { it.startsWith("rssManagement.") }
                                .forEach { remove<Any?>(it) }
                        }
                    RssSourceManagementViewModel(
                        AppRssSourceManagementRepository(),
                        FileRssSourceManagementSessionRepository(),
                        handle,
                    )
                }
            }
        }
    private val importDoc =
        registerForActivityResult(HandleFileContract()) { result ->
            (result.value ?: managementModel.waiting(RssSourceManagementAction.ImportLocal))?.let {
                managementModel.returned(it, result.uri?.toString())
            }
        }
    private val exportResult =
        registerForActivityResult(HandleFileContract()) { result ->
            (result.value ?: managementModel.waiting(RssSourceManagementAction.Export))?.let {
                managementModel.returned(it, result.uri?.toString())
            }
        }
    private var qrLauncher: ActivityResultLauncher<Unit?>? = null
    private var qrNonce: String? = null

    private fun registerQr(nonce: String): ActivityResultLauncher<Unit?> {
        if (qrNonce == nonce) return checkNotNull(qrLauncher)
        qrLauncher?.unregister()
        qrNonce = nonce
        // Registry identity carries this immutable receipt; the shared QR scanner returns only
        // text.
        return activityResultRegistry
            .register("rss-source-qr-$nonce", QrCodeResult()) { result ->
                managementModel.returned(nonce, result)
            }
            .also { qrLauncher = it }
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        managementModel.bind(
            RssSourceManagementLabels(
                getString(R.string.enabled),
                getString(R.string.disabled),
                getString(R.string.need_login),
                getString(R.string.no_group),
            )
        )
        managementModel.waiting(RssSourceManagementAction.ImportQr)?.let(::registerQr)
        onBackPressedDispatcher.addCallback(this) { close() }
    }

    private fun close() {
        if (managementModel.state.value.busy) return
        managementModel.cancelGesture()
        finish()
    }

    private fun ready() = !isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        RssSourceManagementRoute(managementModel, ::ready, ::close, ::native)
    }

    private fun native(request: RssSourceManagementNative, source: RssSource?) {
        try {
            when (request.effect.action) {
                RssSourceManagementAction.Add -> startActivity<RssSourceEditActivity>()
                RssSourceManagementAction.Edit ->
                    if (source != null)
                        startActivity<RssSourceEditActivity> {
                            putExtra("sourceUrl", source.sourceUrl)
                        }
                    else toastOnUi(R.string.rss_source_empty)
                RssSourceManagementAction.ImportLocal ->
                    importDoc.launch {
                        mode = HandleFileContract.FILE
                        allowExtensions = arrayOf("txt", "json")
                        value = request.effect.nonce
                    }
                RssSourceManagementAction.ImportQr -> registerQr(request.effect.nonce).launch(null)
                RssSourceManagementAction.ImportUrl,
                RssSourceManagementAction.ImportInput ->
                    request.input?.let { showDialogFragment(ImportRssSourceDialog(it)) }
                RssSourceManagementAction.Export ->
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
                RssSourceManagementAction.Share -> request.export?.let { share(File(it.path)) }
                RssSourceManagementAction.Help -> showHelp("SourceMRssHelp")
                RssSourceManagementAction.Groups -> showDialogFragment<GroupManageDialog>()
                RssSourceManagementAction.Copy -> request.input?.let { sendToClip(it) }
            }
        } catch (error: Exception) {
            // The receipt was consumed before launch. A failed picker must release its waiting
            // ticket.
            managementModel.returned(request.effect.nonce, null)
            toastOnUi(error.localizedMessage ?: error.javaClass.simpleName)
        }
    }

    override fun onDestroy() {
        qrLauncher?.unregister()
        qrLauncher = null
        super.onDestroy()
    }
}
