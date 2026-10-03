package io.legado.app.ui.association

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import io.legado.app.ui.association.compose.OnlineAssociationCompatibilityModel

/** Public online methods delegate to the reviewed private owner pipeline. */
class OnLineImportViewModel(
    application: Application,
    savedState: SavedStateHandle,
) : OnlineAssociationCompatibilityModel(application, savedState) {
    constructor(application: Application) : this(application, SavedStateHandle())
}
