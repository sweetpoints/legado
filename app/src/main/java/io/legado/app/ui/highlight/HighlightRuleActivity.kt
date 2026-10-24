package io.legado.app.ui.highlight

import android.os.Bundle
import androidx.activity.viewModels
import android.content.Intent
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import io.legado.app.utils.launchCleanup
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseThemedActivity
import io.legado.app.data.repository.*
import io.legado.app.help.DirectLinkUpload
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportHighlightRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.utils.*

class HighlightRuleActivity : BaseThemedActivity() {
    val viewModel by
        viewModels<HighlightManagementViewModel> {
            viewModelFactory {
                initializer {
                    HighlightManagementViewModel(
                        createSavedStateHandle(),
                        RoomHighlightManagementRepository(),
                        FileHighlightManagementSessionRepository(applicationContext),
                    )
                }
            }
        }
    private var exportToken: String? = null
    private val transfer by lazy { FileHighlightManagementTransferRepository(applicationContext) }
    private val importDoc =
        registerForActivityResult(HandleFileContract()) {
            viewModel.importResult(it.uri?.toString())
        }
    private val exportDoc =
        registerForActivityResult(HandleFileContract()) { result ->
            val token = result.value ?: exportToken
            if (token != null) viewModel.exportResult(result.uri?.toString(), token)
            if (token == exportToken) exportToken = null
        }

    override fun createContentView() { setContentView(FrameLayout(this).apply { id = R.id.highlight_manager_flutter_content }) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        exportToken = savedInstanceState?.getString("highlight.export.token")
        onBackPressedDispatcher.addCallback(this) {
            managerFragment?.let { if (it.flutterEngine != null) it.onBackPressed() else viewModel.close() }
                ?: viewModel.close()
        }
        coordinator
        if (managerFragment == null) supportFragmentManager.beginTransaction()
            .add(R.id.highlight_manager_flutter_content, HighlightManagerFlutterFragment(), "highlight-manager-ui").commitNow()
    }
    private val managerFragment get() = supportFragmentManager.findFragmentByTag("highlight-manager-ui") as? HighlightManagerFlutterFragment
    internal val coordinator by lazy {
        HighlightManagerNativeCoordinator(viewModel, transfer, lifecycle, lifecycleScope,
            { !isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved }, { super.finish() },
            ::deliver, { toastOnUi(it) })
    }
    override fun onPostResume() { super.onPostResume(); managerFragment?.onPostResume(); coordinator.onHostReady() }
    override fun onNewIntent(intent: Intent) { managerFragment?.onNewIntent(intent); super.onNewIntent(intent) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); managerFragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); managerFragment?.onActivityResult(requestCode, resultCode, data) }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); managerFragment?.onUserLeaveHint() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); managerFragment?.onTrimMemory(level) }
    override fun onStop() { viewModel.cancelGesture(); lifecycleScope.launchCleanup { runCatching { viewModel.flush() } }; super.onStop() }
    override fun onDestroy() { coordinator.close(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("highlight.export.token", exportToken)
        super.onSaveInstanceState(outState)
    }

    private fun deliver(effect: HighlightManagementEffect, bytes: ByteArray?, file: java.io.File?) {
                when (effect.action) {
                    HighlightManagementAction.Add ->
                        showDialogFragment(HighlightRuleEditDialog.create(pattern = ""))
                    HighlightManagementAction.Edit ->
                        showDialogFragment(HighlightRuleEditDialog.edit(checkNotNull(effect.id)))
                    HighlightManagementAction.Import ->
                        if (effect.text != null)
                            showDialogFragment(ImportHighlightRuleDialog(effect.text))
                        else
                            importDoc.launch {
                                mode = HandleFileContract.FILE
                                allowExtensions = arrayOf("json")
                            }
                    HighlightManagementAction.Groups ->
                        showDialogFragment<HighlightGroupManageDialog>()
                    HighlightManagementAction.Refresh -> ReadBook.upHighlightRules()
                    HighlightManagementAction.Export -> {
                        exportToken = effect.token
                        exportDoc.launch {
                            mode = HandleFileContract.EXPORT
                            value = effect.token
                            fileData =
                                HandleFileContract.FileData(
                                    "HighlightRules.json",
                                    checkNotNull(bytes),
                                    "application/json",
                                )
                        }
                    }
                    HighlightManagementAction.Share -> share(checkNotNull(file))
                    HighlightManagementAction.Copy -> sendToClip(effect.text.orEmpty())
                    HighlightManagementAction.EmptyExport ->
                        toastOnUi(R.string.highlight_rule_empty)
                }
    }

    override fun finish() {
        viewModel.close()
        super.finish()
    }
}
