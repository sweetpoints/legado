package io.legado.app.data.preferences

import io.legado.app.model.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface BackupChoicesStore {
    suspend fun load(group: BackupChoiceGroup): List<BackupChoice>
    suspend fun toggle(group: BackupChoiceGroup, key: String, checked: Boolean)
    suspend fun save()
}
internal interface BackupChoicesRepository {
    suspend fun load(group: BackupChoiceGroup): List<BackupChoice>
    suspend fun toggle(group: BackupChoiceGroup, key: String, checked: Boolean): List<BackupChoice>
    suspend fun save()
}
/** Matches legacy live in-memory choices and their disk save when the selector is dismissed. */
internal class DefaultBackupChoicesRepository(private val store: BackupChoicesStore,
    private val io: CoroutineDispatcher = Dispatchers.IO) : BackupChoicesRepository {
    override suspend fun load(group: BackupChoiceGroup) = gate.withLock { withContext(io) { store.load(group).toList() } }
    override suspend fun toggle(group: BackupChoiceGroup, key: String, checked: Boolean) = gate.withLock {
        withContext(io + NonCancellable) {
            require(store.load(group).any { it.key == key }) { "Unknown backup choice" }
            store.toggle(group, key, checked); store.load(group).toList()
        }
    }
    override suspend fun save() = gate.withLock { withContext(io + NonCancellable) { store.save() } }
    private companion object { val gate = Mutex() }
}
