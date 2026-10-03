package io.legado.app.data.preferences

import io.legado.app.model.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface BackupSettingsStore {
    fun changes(): Flow<Unit>

    suspend fun load(): BackupSettingsSnapshot

    suspend fun text(key: BackupSettingText, value: String)

    suspend fun boolean(key: BackupSettingSwitch, value: Boolean)

    suspend fun automatic(value: AutoBackupSettings)

    suspend fun path(value: String?)

    suspend fun localPassword(value: String)

    suspend fun reconfigureWebDav()

    suspend fun needsHelp(): Boolean
}

internal interface BackupSettingsRepository {
    fun observe(): Flow<BackupSettingsSnapshot>

    suspend fun load(): BackupSettingsSnapshot

    suspend fun text(key: BackupSettingText, value: String)

    suspend fun boolean(key: BackupSettingSwitch, value: Boolean)

    suspend fun automatic(value: AutoBackupSettings)

    suspend fun path(value: String?)

    suspend fun localPassword(value: String)

    suspend fun needsHelp(): Boolean
}

internal class DefaultBackupSettingsRepository(
    private val store: BackupSettingsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BackupSettingsRepository {
    override fun observe() = store.changes().map { store.load() }.flowOn(io)

    override suspend fun load() = withContext(io) { store.load() }

    override suspend fun text(key: BackupSettingText, value: String) = gate.withLock {
        withContext(io + NonCancellable) {
            store.text(key, value)
            if (key.reconfigure) store.reconfigureWebDav()
        }
    }

    override suspend fun boolean(key: BackupSettingSwitch, value: Boolean) = gate.withLock {
        withContext(io + NonCancellable) {
            if (store.load().enabled(key)) store.boolean(key, value)
        }
    }

    override suspend fun automatic(value: AutoBackupSettings) = gate.withLock {
        require(value.intervalDays >= 1) { "Invalid automatic backup interval" }
        withContext(io + NonCancellable) { store.automatic(value) }
    }

    override suspend fun path(value: String?) = gate.withLock {
        withContext(io + NonCancellable) { store.path(value?.takeIf { it.isNotEmpty() }) }
    }

    override suspend fun localPassword(value: String) = gate.withLock {
        withContext(io + NonCancellable) { store.localPassword(value) }
    }

    override suspend fun needsHelp() = withContext(io) { store.needsHelp() }

    private companion object {
        val gate = Mutex()
    }
}
