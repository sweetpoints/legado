package io.legado.app.ui.association

import io.legado.app.ui.association.compose.AssociationDataImportDialog

/** Public confirmation identity and constructors retained over the private Compose dialog. */
class ImportDataDialog() : AssociationDataImportDialog() {
    constructor(type: String, source: String) : this() {
        initializeLegacyRequest(type, source)
    }

    companion object {
        fun fromSession(ticket: String) = ImportDataDialog().apply { initializeSession(ticket) }
    }
}
