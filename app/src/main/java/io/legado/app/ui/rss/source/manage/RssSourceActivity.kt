package io.legado.app.ui.rss.source.manage

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseThemedActivity
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
import kotlinx.coroutines.launch

/**
 * Flutter presentation with native source management. Parsing, imports and source cleanup retain
 * their established pipelines.
 */
class RssSourceActivity : BaseThemedActivity() {
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

    private val managerBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = close()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Restored Flutter callbacks must be registered after this native fallback.
        onBackPressedDispatcher.addCallback(managerBackCallback)
        super.onCreate(savedInstanceState)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        managementModel.bind(
            RssSourceManagementLabels(
                getString(R.string.enabled),
                getString(R.string.disabled),
                getString(R.string.need_login),
                getString(R.string.no_group),
            )
        )
        managementModel.waiting(RssSourceManagementAction.ImportQr)?.let(::registerQr)
        if (rssFragment == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.rss_manager_flutter_container, RssManagerFlutterFragment(), FLUTTER_FRAGMENT_TAG)
                .commitNow()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                managementModel.state.collect { state ->
                    val pending = state.pending ?: return@collect
                    if (!state.loaded || !ready()) return@collect
                    try {
                        val request = managementModel.native(pending.nonce) ?: return@collect
                        val source = if (pending.action == RssSourceManagementAction.Edit)
                            request.sourceId?.let { managementModel.source(it) } else null
                        if (lifecycle.currentState == Lifecycle.State.RESUMED && ready() &&
                            managementModel.delivered(pending.nonce)) native(request, source)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        managementModel.delivered(pending.nonce)
                        managementModel.failed(getString(R.string.error))
                    }
                }
            }
        }
    }

    internal fun close() {
        if (managementModel.state.value.busy) return
        managementModel.cancelGesture()
        if (managementModel.state.value.dialog != null) managementModel.cancelDialog()
        else finish()
    }

    private fun ready() = !isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved

    override fun createContentView() {
        setContentView(FrameLayout(this).apply { id = R.id.rss_manager_flutter_container })
    }

    private val rssFragment: RssManagerFlutterFragment?
        get() = supportFragmentManager.findFragmentByTag(FLUTTER_FRAGMENT_TAG) as? RssManagerFlutterFragment

    override fun onPostResume() {
        super.onPostResume()
        rssFragment?.onPostResume()
    }

    override fun onNewIntent(intent: Intent) {
        rssFragment?.onNewIntent(intent)
        super.onNewIntent(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        rssFragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        rssFragment?.onActivityResult(requestCode, resultCode, data)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        rssFragment?.onUserLeaveHint()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        rssFragment?.onTrimMemory(level)
    }

    companion object {
        internal const val FLUTTER_FRAGMENT_TAG = "rss-manager-flutter"
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
        managerBackCallback.remove()
        qrLauncher?.unregister()
        qrLauncher = null
        super.onDestroy()
    }
}
