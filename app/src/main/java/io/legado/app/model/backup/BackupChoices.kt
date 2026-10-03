package io.legado.app.model.backup

internal enum class BackupChoiceGroup { Content, Ignore }
internal data class BackupChoice(val key: String, val title: String, val checked: Boolean)
