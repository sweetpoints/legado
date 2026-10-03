package io.legado.app.model.settings

internal enum class OtherEditor { UserAgent, Hosts, Token, PreDownload, Threads, WebPort, McpPort, BitmapCache, ImageRetain, SourceLines, Language, Home }
internal enum class OtherMutationKind { Boolean, Number, Text, Choice }
/** The prepared effect plan is durable before applying preferences; a restored interrupted apply needs confirmation. */
internal data class OtherMutation(val id: String, val kind: OtherMutationKind, val key: String, val boolean: Boolean? = null,
    val number: Int? = null, val text: String? = null, val effects: List<OtherEffect> = emptyList(), val accepted: Boolean = false)
internal data class OtherEffectReceipt(val id: String, val effect: OtherEffect)
internal data class OtherSettingsDraft(val revision: Long = 0, val editor: OtherEditor? = null, val text: String = "",
    val selectionStart: Int = 0, val selectionEnd: Int = 0, val mutation: OtherMutation? = null, val effects: List<OtherEffectReceipt> = emptyList())
