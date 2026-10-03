package io.legado.app.model.backup

internal data class BackupRestoreFiles(
    val names: List<String>,
    val truncatedCloudListing: Boolean = false,
)
