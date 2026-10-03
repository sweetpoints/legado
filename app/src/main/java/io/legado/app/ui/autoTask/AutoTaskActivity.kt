package io.legado.app.ui.autoTask

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.RoomAutoTaskManagementRepository
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

class AutoTaskActivity : BaseComposeActivity() {
    val viewModel by
        viewModels<AutoTaskManagementViewModel> {
            viewModelFactory {
                initializer {
                    AutoTaskManagementViewModel(
                        RoomAutoTaskManagementRepository(applicationContext),
                        createSavedStateHandle(),
                    )
                }
            }
        }
    private val importDoc =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { showDialogFragment(ImportAutoTaskDialog(it.toString())) }
        }
    private val exportDoc =
        registerForActivityResult(HandleFileContract()) { result ->
            result.uri?.let { viewModel.exportReturned(it.toString()) }
        }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        AutoTaskManagementRoute(
            viewModel,
            ::deliver,
            { toastOnUi(it) },
            { !supportFragmentManager.isStateSaved },
        )
    }

    private fun deliver(effect: AutoTaskManagementEffect, payload: String?) {
        when (effect.action) {
            AutoTaskManagementAction.Edit ->
                effect.value?.let { startActivity(AutoTaskEditActivity.intent(this, it)) }
                    ?: startActivity<AutoTaskEditActivity>()
            AutoTaskManagementAction.Debug ->
                startActivity(AutoTaskDebugActivity.intent(this, effect.value!!))
            AutoTaskManagementAction.Login ->
                startActivity<SourceLoginActivity> {
                    putExtra("type", "autoTask")
                    putExtra("key", effect.value)
                }
            AutoTaskManagementAction.ImportLocal ->
                importDoc.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            AutoTaskManagementAction.ImportDraft ->
                showDialogFragment(ImportAutoTaskDialog(requireNotNull(payload)))
            AutoTaskManagementAction.Export ->
                exportDoc.launch {
                    mode = HandleFileContract.EXPORT
                    fileData =
                        HandleFileContract.FileData(
                            effect.name!!,
                            requireNotNull(payload),
                            "application/json",
                        )
                }
            AutoTaskManagementAction.Help -> showHelp("autoTaskHelp")
            AutoTaskManagementAction.Close -> finish()
            AutoTaskManagementAction.Clipboard -> sendToClip(effect.value!!)
        }
    }

    override fun onPause() {
        viewModel.cancelSlide()
        super.onPause()
    }
}
