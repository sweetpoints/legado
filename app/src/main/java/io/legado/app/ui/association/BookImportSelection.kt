package io.legado.app.ui.association

internal data class ImportBookSourceStatus(
    val isNew: Boolean,
    val isUpdate: Boolean,
) {
    val shouldSelect: Boolean
        get() = isNew || isUpdate
}

internal fun resolveImportBookSourceStatus(
    importedLastUpdateTime: Long,
    localLastUpdateTime: Long?,
): ImportBookSourceStatus {
    return ImportBookSourceStatus(
        isNew = localLastUpdateTime == null,
        isUpdate = localLastUpdateTime != null && localLastUpdateTime < importedLastUpdateTime,
    )
}

internal fun resolveImportSourceSelection(
    status: ImportBookSourceStatus,
    manualSelection: Boolean?,
    selectExisting: Boolean = false,
): Boolean {
    return manualSelection ?: (selectExisting || status.shouldSelect)
}
