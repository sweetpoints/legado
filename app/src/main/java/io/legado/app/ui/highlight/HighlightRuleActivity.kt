package io.legado.app.ui.highlight

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.*
import io.legado.app.help.DirectLinkUpload
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportHighlightRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.utils.*

class HighlightRuleActivity:BaseComposeActivity() {
    val viewModel by viewModels<HighlightManagementViewModel>{viewModelFactory{initializer{
        HighlightManagementViewModel(createSavedStateHandle(),RoomHighlightManagementRepository(),FileHighlightManagementSessionRepository(applicationContext))
    }}}
    private var exportToken:String?=null
    private val transfer by lazy{FileHighlightManagementTransferRepository(applicationContext)}
    private val importDoc=registerForActivityResult(HandleFileContract()){viewModel.importResult(it.uri?.toString())}
    private val exportDoc=registerForActivityResult(HandleFileContract()){result ->
        val token=result.value ?: exportToken
        if(token!=null)viewModel.exportResult(result.uri?.toString(),token)
        if(token==exportToken)exportToken=null
    }
    override fun onComposeCreated(savedInstanceState:Bundle?) {exportToken=savedInstanceState?.getString("highlight.export.token")}
    override fun onSaveInstanceState(outState:Bundle) {outState.putString("highlight.export.token",exportToken);super.onSaveInstanceState(outState)}
    @Composable override fun Content(savedInstanceState:Bundle?) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val summary=if(state.draft.exportResult.isAbsUrl())DirectLinkUpload.getSummary() else null
        HighlightManagementRoute(viewModel,transfer,{super.finish()}, {effect,bytes,file ->when(effect.action){
            HighlightManagementAction.Add->showDialogFragment(HighlightRuleEditDialog.create(pattern=""))
            HighlightManagementAction.Edit->showDialogFragment(HighlightRuleEditDialog.edit(checkNotNull(effect.id)))
            HighlightManagementAction.Import->if(effect.text!=null)showDialogFragment(ImportHighlightRuleDialog(effect.text)) else importDoc.launch{mode=HandleFileContract.FILE;allowExtensions=arrayOf("json")}
            HighlightManagementAction.Groups->showDialogFragment<HighlightGroupManageDialog>()
            HighlightManagementAction.Refresh->ReadBook.upHighlightRules()
            HighlightManagementAction.Export->{
                exportToken=effect.token
                exportDoc.launch{mode=HandleFileContract.EXPORT;value=effect.token;fileData=HandleFileContract.FileData("HighlightRules.json",checkNotNull(bytes),"application/json")}
            }
            HighlightManagementAction.Share->share(checkNotNull(file))
            HighlightManagementAction.Copy->sendToClip(effect.text.orEmpty())
            HighlightManagementAction.EmptyExport->toastOnUi(R.string.highlight_rule_empty)
        }},{toastOnUi(it)},summary,{!supportFragmentManager.isStateSaved})
    }
    override fun finish(){viewModel.close();super.finish()}
}
