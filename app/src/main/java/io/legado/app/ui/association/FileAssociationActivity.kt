package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.associationSupportedSharedImportMimeType
import io.legado.app.ui.association.compose.AssociationComposeActivity

/** Public ACTION_SEND, ACTION_SEND_MULTIPLE and ACTION_VIEW transparent import entry. */
class FileAssociationActivity : AssociationComposeActivity() {
    override val hostKind: AssociationHostKind
        get() = AssociationHostKind.File

    override val importModelClass: Class<FileAssociationViewModel>
        get() = FileAssociationViewModel::class.java

    override fun createImportModel(savedState: SavedStateHandle) =
        FileAssociationViewModel(application, savedState)
}

internal fun isSupportedSharedImportMimeType(mimeType: String?): Boolean =
    associationSupportedSharedImportMimeType(mimeType)
