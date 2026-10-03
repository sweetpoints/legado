package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.ui.association.compose.AssociationComposeActivity

/** Public legado/yuedu one-click import scheme entry. */
class OnLineImportActivity : AssociationComposeActivity() {
    override val hostKind: AssociationHostKind
        get() = AssociationHostKind.Online

    override val importModelClass: Class<OnLineImportViewModel>
        get() = OnLineImportViewModel::class.java

    override fun createImportModel(savedState: SavedStateHandle) =
        OnLineImportViewModel(application, savedState)
}
