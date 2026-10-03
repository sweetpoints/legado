package io.legado.app.data.preferences

import io.legado.app.model.cover.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface CoverFontSettingsStore {
    fun changes(): Flow<Unit>
    suspend fun load(): CoverFontSettingsSnapshot
    suspend fun boolean(key: CoverFontSwitch, value: Boolean)
    suspend fun size(key: CoverFontSize, value: Int)
    suspend fun stageFont(path: String): String
    suspend fun font(path: String)
    suspend fun refreshCover()
    suspend fun refreshPreviewAndBookshelf()
}
internal interface CoverFontSettingsRepository {
    fun observe(): Flow<CoverFontSettingsSnapshot>
    suspend fun load(): CoverFontSettingsSnapshot
    suspend fun boolean(key: CoverFontSwitch, value: Boolean)
    suspend fun size(key: CoverFontSize, value: Int)
    suspend fun font(path: String)
}
internal class DefaultCoverFontSettingsRepository(private val store: CoverFontSettingsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO, private val main: CoroutineDispatcher = Dispatchers.Main.immediate) : CoverFontSettingsRepository {
    override fun observe() = store.changes().map { store.load() }.flowOn(io)
    override suspend fun load() = withContext(io) { store.load() }
    private suspend fun commit(action: suspend () -> Unit) = withContext(io + NonCancellable) {
        action(); store.refreshCover(); withContext(main) { store.refreshPreviewAndBookshelf() }
    }
    override suspend fun boolean(key: CoverFontSwitch, value: Boolean) = gate.withLock { commit { store.boolean(key, value) } }
    override suspend fun size(key: CoverFontSize, value: Int) = gate.withLock {
        if (withContext(io) { store.load().customSizesEnabled }) commit { store.size(key, value.coerceIn(50, 200)) }
    }
    override suspend fun font(path: String) = gate.withLock {
        val installed = if (path.isEmpty()) "" else withContext(io) { store.stageFont(path).also { currentCoroutineContext().ensureActive() } }
        commit { store.font(installed) }
    }
    private companion object { val gate = Mutex() }
}
