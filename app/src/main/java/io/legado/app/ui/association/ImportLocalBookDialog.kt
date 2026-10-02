package io.legado.app.ui.association

import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.FileSharedLocalBookPreviewRepository
import io.legado.app.data.repository.SharedLocalBookPreviewSeed
import io.legado.app.utils.setLayout

/** Pure Compose preview over the existing shared-book staging, copy and parser pipeline. */
class ImportLocalBookDialog : BaseComposeDialogFragment() {
    private val pipeline by activityViewModels<FileAssociationViewModel>()
    internal val model by viewModels<SharedLocalBookPreviewViewModel> {
        viewModelFactory { initializer { SharedLocalBookPreviewViewModel(FileSharedLocalBookPreviewRepository(), createSavedStateHandle()) } }
    }
    override fun onStart() { super.onStart(); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        pipeline.localBookBatch.observe(viewLifecycleOwner) { batch ->
            model.batch(batch.orEmpty().map { item ->
                val title = item.preview?.let { if (it.author.isBlank()) it.name else "${it.name} / ${it.author}" }
                SharedLocalBookPreviewSeed(item.file.uri.toString(), title, item.isOnBookShelf, item.isDir)
            }, pipeline.selectedLocalBooks.map { it.toString() })
        }
        pipeline.importingLocalBooks.observe(viewLifecycleOwner) { sourceState() }
        pipeline.localBookDestination.observe(viewLifecycleOwner) { sourceState() }
    }
    private fun sourceState() = model.source(pipeline.importingLocalBooks.value == true, pipeline.localBookDestination.value == true)
    private fun currentUris() = pipeline.localBookBatch.value.orEmpty().filter { !it.isDir && !it.isOnBookShelf }
        .associate { FileSharedLocalBookPreviewRepository.id(it.file.uri.toString()) to it.file.uri.toString() }
    private fun selection(uris: List<String>) { pipeline.updateLocalSelection(uris.map(Uri::parse)) }
    private fun effect(action: SharedLocalBookPreviewAction, uris: List<String>) {
        selection(uris)
        when (action) {
            SharedLocalBookPreviewAction.Import -> pipeline.confirmLocalBooks()
            SharedLocalBookPreviewAction.Directory -> pipeline.requestLocalBookDirectory(false)
        }
        sourceState()
    }
    @Composable override fun Content() {
        SharedLocalBookPreviewRoute(model, { isAdded && !parentFragmentManager.isStateSaved }, ::currentUris,
            ::selection, ::effect, ::dismissAllowingStateLoss, { isCancelable = it })
    }
    override fun onCancel(dialog: DialogInterface) { model.cancel(); super.onCancel(dialog) }
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity?.isChangingConfigurations != true) activity?.finish()
    }
}
