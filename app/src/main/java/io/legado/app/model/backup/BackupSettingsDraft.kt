package io.legado.app.model.backup

internal enum class BackupForm { Url, Account, Password, Directory, Device, LocalPassword, Automatic, Content, Ignore }
internal enum class BackupTaskKind { Backup, RestoreNames, RestoreWebDav, RestoreLocal, ImportOld, LanReceive }
internal enum class BackupTaskPhase { Requested, Running, Complete }
/** Arbitrary text, credentials, QR descriptors and restore names are private files, never Bundle values. */
internal data class BackupTaskDraft(val id: String, val kind: BackupTaskKind, val payload: String? = null,
    val uploadWebDav: Boolean = true, val phase: BackupTaskPhase = BackupTaskPhase.Requested)
internal data class BackupSettingsDraft(val revision: Long = 0, val form: BackupForm? = null,
    val text: String = "", val autoEnabled: Boolean = true, val autoWebDav: Boolean = true, val intervalText: String = "1",
    val choices: Map<String, Boolean> = emptyMap(), val restoreNames: List<String> = emptyList(), val task: BackupTaskDraft? = null)
