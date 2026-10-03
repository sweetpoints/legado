package io.legado.app.model.backup

internal data class BackupLanOffer(val id: String, val imagePath: String, val expiresAt: Long)

internal data class BackupLanReceiveInfo(val bytes: Long, val device: String)

internal enum class BackupLanReceivePhase {
    Receiving,
    BackingUp,
    Restoring,
    Complete,
}
