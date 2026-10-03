package io.legado.app.ui.association.compose

import android.content.Context
import io.legado.app.data.association.FileAssociationNativeResultRepository
import io.legado.app.data.association.FileAssociationSessionRepository
import io.legado.app.data.association.HttpAssociationOnlineRepository
import io.legado.app.data.association.LegacyAssociationImportOperations
import io.legado.app.data.association.LocalAssociationFileRepository

/** Application-only engines shared by the Compose host and its compatibility projections. */
internal class AssociationDependencies(context: Context) {
    val sessions = FileAssociationSessionRepository(context)
    val nativeResults = FileAssociationNativeResultRepository(sessions)
    val files = LocalAssociationFileRepository(context, sessions)
    val online = HttpAssociationOnlineRepository(context, sessions)
    val actions = LegacyAssociationImportOperations(context, sessions)
}
