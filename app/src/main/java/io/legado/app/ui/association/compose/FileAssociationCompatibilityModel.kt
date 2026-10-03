package io.legado.app.ui.association.compose

import android.app.Application
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.data.association.AssociationNativeKind
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only compatibility for the existing Compose local-preview/confirmation dialogs. This new
 * class is not yet installed by either legacy Host; replacing those concrete VMs is a later step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class FileAssociationCompatibilityModel
internal constructor(
    savedState: SavedStateHandle,
    dependencies: AssociationDependencies,
) :
    AssociationImportViewModel(
        savedState,
        dependencies.sessions,
        dependencies.files,
        dependencies.online,
        dependencies.actions,
        dependencies.nativeResults,
    ) {
    constructor(
        application: Application,
        savedState: SavedStateHandle,
    ) : this(
        savedState,
        AssociationDependencies(application),
    )

    private var selectionProof: Deferred<Boolean>? = null

    val localBookBatch: LiveData<List<ImportBook>?> =
        state
            .mapLatest { current ->
                val previews = current.session?.previews.orEmpty()
                if (previews.isEmpty()) null
                else
                    try {
                        withContext(Dispatchers.IO) {
                            previews.map { preview ->
                                ImportBook(
                                    file = FileDoc.fromUri(Uri.parse(preview.fileUri), false),
                                    isOnBookShelf = preview.onBookshelf,
                                    preview =
                                        GSON.fromJsonObject<Book>(preview.bookJson).getOrThrow(),
                                )
                            }
                        }
                    } catch (failure: Throwable) {
                        currentCoroutineContext().ensureActive()
                        reportProjectionFailure(
                            current.ticket,
                            current.session?.generation,
                            failure,
                        )
                        null
                    }
            }
            .asLiveData()

    val localBookDestination =
        state
            .map { it.session?.phase == AssociationPhase.Directory }
            .distinctUntilChanged()
            .asLiveData()
    val importingLocalBooks =
        state
            .map { it.busy && it.session?.operation?.kind == "local-import" }
            .distinctUntilChanged()
            .asLiveData()
    val importedLocalBooks =
        state
            .map { current ->
                current.session?.let {
                    it.phase == AssociationPhase.Finished && it.previews.isNotEmpty()
                } == true
            }
            .distinctUntilChanged()
            .asLiveData()
    val importingData =
        state
            .map { it.busy && it.session?.operation?.kind in DATA_IMPORTS }
            .distinctUntilChanged()
            .asLiveData()
    val importedData =
        state
            .map { current ->
                current.session?.let {
                    it.phase == AssociationPhase.Finished && it.importType in DATA_IMPORTS
                } == true
            }
            .distinctUntilChanged()
            .asLiveData()
    val errorLive =
        state.map { it.restoreError ?: it.session?.error }.distinctUntilChanged().asLiveData()

    val selectedLocalBooks: Set<Uri>
        get() =
            state.value.session
                ?.let { session ->
                    session.previews
                        .filter { it.id in session.selectedIds }
                        .map { Uri.parse(it.fileUri) }
                        .toSet()
                }
                .orEmpty()

    val pendingLocalBooks: List<ImportBook>
        get() = localBookBatch.value.orEmpty().filter { it.file.uri in selectedLocalBooks }

    val choosingLocalBookDirectory: Boolean
        get() = state.value.session?.choosingDirectory == true

    val importAfterDirectorySelection: Boolean
        get() = state.value.session?.importAfterDirectory ?: true

    fun updateLocalSelection(uris: Collection<Uri>) {
        val selected = uris.map(Uri::toString).toSet()
        selectionProof =
            updateSelectionWithProof(
                state.value.session
                    ?.previews
                    .orEmpty()
                    .filter { it.fileUri in selected }
                    .map { it.id }
                    .toSet()
            )
    }

    fun confirmLocalBooks() {
        val proof = selectionProof ?: return
        val owner = state.value
        viewModelScope.launch {
            if (!proof.await() || selectionProof !== proof) return@launch
            // The child issues selection and confirmation in the same callback. Wait for the
            // durable selection command before choosing a folder or accepting the import.
            awaitCommands()
            val configured = withContext(Dispatchers.IO) { AppConfig.defaultBookTreeUri }
            if (
                !acceptsCallback(owner.ticket, owner.session?.generation) ||
                    selectionProof !== proof
            )
                return@launch
            if (configured.isNullOrBlank()) requestDirectory()
            else confirmOperation("local-import", configured)
        }
    }

    fun requestLocalBookDirectory(importAfter: Boolean) {
        viewModelScope.launch {
            awaitCommands()
            requestDirectory(importAfter)
        }
    }

    fun selectLocalBookDirectory(directory: Uri?) {
        viewModelScope.launch {
            awaitCommands()
            val pending =
                state.value.session?.claimedEffects?.firstOrNull {
                    it.kind == AssociationNativeKind.SelectDirectory
                }
            if (pending != null) directoryResult(pending, directory?.toString())
            else if (directory == null || !importAfterDirectorySelection) cancelDirectory()
            else confirmOperation("local-import", directory.toString())
        }
    }

    fun importLocalBooks(directory: Uri) = confirmOperation("local-import", directory.toString())

    fun importData(type: String, source: String) {
        val session = state.value.session ?: return
        if (session.importType != type || session.importSource != source) return
        confirmOperation(type)
    }

    private companion object {
        val DATA_IMPORTS = setOf("backup", "bookshelf")
    }
}
