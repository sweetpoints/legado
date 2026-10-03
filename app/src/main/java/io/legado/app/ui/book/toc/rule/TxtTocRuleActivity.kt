package io.legado.app.ui.book.toc.rule

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.RoomTxtTocRuleManagementRepository
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import java.io.File

class TxtTocRuleActivity : BaseComposeActivity(), TxtTocRuleEditDialog.Callback {
    val viewModel by
        viewModels<TxtTocRuleManagementViewModel> {
            viewModelFactory {
                initializer {
                    TxtTocRuleManagementViewModel(
                        RoomTxtTocRuleManagementRepository(applicationContext),
                        createSavedStateHandle(),
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

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        TxtTocRuleManagementRoute(
            viewModel,
            ::finish,
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
            ::deliver,
        )
    }

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
