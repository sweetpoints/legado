package io.legado.app.data.preferences

import io.legado.app.model.cover.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface CoverSettingsStore {
    fun changes(): Flow<Unit>

    suspend fun load(): CoverSettingsSnapshot

    suspend fun boolean(key: CoverSettingSwitch, value: Boolean)

    suspend fun stageImage(uri: String): String

    suspend fun image(key: CoverSettingImage, path: String?)

    suspend fun refreshCover()

    suspend fun refreshBookshelf()
}

internal interface CoverSettingsRepository {
    fun observe(): Flow<CoverSettingsSnapshot>

    suspend fun load(): CoverSettingsSnapshot

    suspend fun boolean(key: CoverSettingSwitch, value: Boolean)

    suspend fun image(key: CoverSettingImage, uri: String?)
}

internal class DefaultCoverSettingsRepository(
    private val store: CoverSettingsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : CoverSettingsRepository {
    override fun observe() = store.changes().map { store.load() }.flowOn(io)

    override suspend fun load() = withContext(io) { store.load() }

    override suspend fun boolean(key: CoverSettingSwitch, value: Boolean) = gate.withLock {
        withContext(io + NonCancellable) {
            if (!store.load().enabled(key)) return@withContext
            store.boolean(key, value)
            if (key.refresh) {
                store.refreshCover()
                withContext(main) { store.refreshBookshelf() }
            }
        }
    }

    override suspend fun image(key: CoverSettingImage, uri: String?) = gate.withLock {
        val path = uri?.let {
            withContext(io) {
                store.stageImage(it).also { currentCoroutineContext().ensureActive() }
            }
        }
        withContext(io + NonCancellable) {
            store.image(key, path)
            store.refreshCover()
        }
    }

    private companion object {
        val gate = Mutex()
    }
}
