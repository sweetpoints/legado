package io.legado.app.ui.association

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.association.associationSharedImportUrl
import io.legado.app.ui.association.compose.FileAssociationCompatibilityModel

/** Public child-dialog model identity; immutable state and IO belong to the reviewed pipeline. */
class FileAssociationViewModel(
    application: Application,
    savedState: SavedStateHandle,
) : FileAssociationCompatibilityModel(application, savedState)

internal fun extractSharedImportUrl(text: String): String? = associationSharedImportUrl(text)
