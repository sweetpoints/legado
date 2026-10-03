package io.legado.app.ui.autoTask

import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.data.repository.sameAutoTaskImportConfiguration

/** Kept for import/export callers that compare configurations without runtime metadata. */
internal fun sameAutoTaskForImport(left: AutoTaskRule, right: AutoTaskRule): Boolean =
    sameAutoTaskImportConfiguration(left, right)
