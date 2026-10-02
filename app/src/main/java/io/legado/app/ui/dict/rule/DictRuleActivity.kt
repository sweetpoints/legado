package io.legado.app.ui.dict.rule

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.RoomDictionaryRuleManagementRepository
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import java.io.File

class DictRuleActivity : BaseComposeActivity() {
    val viewModel by viewModels<DictionaryRuleManagementViewModel> {
        viewModelFactory { initializer { DictionaryRuleManagementViewModel(RoomDictionaryRuleManagementRepository(applicationContext), createSavedStateHandle()) } }
    }
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) { value ->
        value?.let { showDialogFragment(ImportDictRuleDialog(it)) }
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { showDialogFragment(ImportDictRuleDialog(it.toString())) }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { viewModel.exportFinished(it.toString()) }
    }
    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        DictionaryRuleManagementRoute(viewModel, ::finish,
            { showDialogFragment(DictRuleEditDialog()) },
            { showDialogFragment(DictRuleEditDialog(it)) },
            { importDoc.launch { mode = HandleFileContract.FILE; allowExtensions = arrayOf("txt", "json") } },
            { qrCodeResult.launch(null) }, { showHelp("dictRuleHelp") }, ::deliver)
    }
    private fun deliver(effect: DictionaryManagementEffect) {
        when (effect.kind) {
            DictionaryManagementEffectKind.ShareFile -> share(File(effect.value))
            DictionaryManagementEffectKind.ExportJson -> exportResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData("exportDictRule.json", effect.value, "application/json")
            }
            DictionaryManagementEffectKind.ImportText -> showDialogFragment(ImportDictRuleDialog(effect.value))
            DictionaryManagementEffectKind.Clipboard -> sendToClip(effect.value)
        }
    }
    override fun onPause() {
        viewModel.cancelGestures()
        super.onPause()
    }
}
