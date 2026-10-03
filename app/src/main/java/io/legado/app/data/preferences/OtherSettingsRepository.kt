package io.legado.app.data.preferences

import com.google.gson.JsonParser
import io.legado.app.help.config.normalizeJsSourceApiToken
import io.legado.app.model.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface OtherSettingsStore {
    fun changes(): Flow<Unit>
    suspend fun initializeProcessText()
    suspend fun load(): OtherSettingsSnapshot
    suspend fun readText(key: OtherText): String
    suspend fun boolean(key: OtherSwitch, value: Boolean)
    suspend fun number(key: OtherNumber, value: Int)
    suspend fun text(key: OtherText, value: String?)
    suspend fun choice(key: OtherChoice, value: String)
}
internal interface OtherSettingsRepository {
    fun observe(): Flow<OtherSettingsSnapshot>
    suspend fun load(): OtherSettingsSnapshot
    suspend fun readText(key: OtherText): String
    suspend fun reconcileProcessText() {}
    suspend fun boolean(key: OtherSwitch, value: Boolean): List<OtherEffect>
    suspend fun number(key: OtherNumber, value: Int): List<OtherEffect>
    suspend fun text(key: OtherText, value: String): List<OtherEffect>
    suspend fun choice(key: OtherChoice, value: String): List<OtherEffect>
}
/** Accepted preference mutations are serialized. Platform effects are immutable ids delivered later on Main. */
internal class DefaultOtherSettingsRepository(private val store: OtherSettingsStore, private val io: CoroutineDispatcher = Dispatchers.IO) : OtherSettingsRepository {
    override fun observe(): Flow<OtherSettingsSnapshot> = flow {
        gate.withLock { withContext(NonCancellable) { store.initializeProcessText() } }
        emitAll(store.changes().map { store.load() })
    }.flowOn(io)
    override suspend fun load() = withContext(io) { store.load() }
    override suspend fun readText(key: OtherText) = withContext(io) { store.readText(key) }
    override suspend fun reconcileProcessText() { accepted { store.boolean(OtherSwitch.ProcessText, store.load().switches.getValue(OtherSwitch.ProcessText)); emptyList() } }
    override suspend fun boolean(key: OtherSwitch, value: Boolean): List<OtherEffect> = accepted {
        val old = store.load()
        if (!old.visible(key) && !(key == OtherSwitch.LiveNotifications && !value) || old.switches.getValue(key) == value) return@accepted emptyList()
        store.boolean(key, value)
        when (key) {
            OtherSwitch.TokenRequired -> listOf(OtherEffect.RestartWeb, OtherEffect.RestartMcp)
            OtherSwitch.Log -> listOf(OtherEffect.LogConfiguration)
            OtherSwitch.Cronet -> if (value) listOf(OtherEffect.DownloadCronet) else emptyList()
            OtherSwitch.Discovery, OtherSwitch.Rss -> listOf(OtherEffect.NotifyMain)
            OtherSwitch.LiveNotifications -> if (value) listOf(OtherEffect.PromotedNotificationSettings) else emptyList()
            else -> emptyList()
        }
    }
    override suspend fun number(key: OtherNumber, value: Int): List<OtherEffect> = accepted {
        val number = value.coerceIn(key.minimum, key.maximum)
        if (store.load().numbers.getValue(key) == number) return@accepted emptyList()
        store.number(key, number)
        when (key) { OtherNumber.Threads -> listOf(OtherEffect.ThreadsChanged); OtherNumber.WebPort -> listOf(OtherEffect.RestartWeb)
            OtherNumber.McpPort -> listOf(OtherEffect.RestartMcp); OtherNumber.BitmapCache -> listOf(OtherEffect.ResizeBitmapCache); else -> emptyList() }
    }
    override suspend fun text(key: OtherText, value: String): List<OtherEffect> = accepted {
        val normalized = when (key) {
            OtherText.UserAgent -> value.takeUnless { it.isBlank() }
            OtherText.Hosts -> value.takeIf { runCatching { JsonParser.parseString(it).isJsonObject }.getOrDefault(false) }
            OtherText.Token -> normalizeJsSourceApiToken(value)
            OtherText.BookTree -> value
        }
        val previous = store.readText(key).takeIf { it.isNotEmpty() }
        if (previous == normalized) return@accepted emptyList()
        store.text(key, normalized)
        if (key == OtherText.Token && store.load().switches.getValue(OtherSwitch.TokenRequired))
            listOf(if (normalized == null) OtherEffect.StopMcp else OtherEffect.RestartMcp) else emptyList()
    }
    override suspend fun choice(key: OtherChoice, value: String): List<OtherEffect> = accepted {
        if (store.load().choices.getValue(key) == value) return@accepted emptyList()
        store.choice(key, value)
        if (key == OtherChoice.Language) listOf(OtherEffect.RestartApplication) else emptyList()
    }
    private suspend fun accepted(action: suspend () -> List<OtherEffect>) = gate.withLock { withContext(io + NonCancellable) { action().toList() } }
    private companion object { val gate = Mutex() }
}
