package io.legado.app.data.preferences

import io.legado.app.model.welcome.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface WelcomeSettingsStore {
    fun changes(): Flow<Unit>

    suspend fun load(): WelcomeSettingsSnapshot

    suspend fun milliseconds(value: Int)

    suspend fun boolean(key: WelcomeSwitch, value: Boolean)

    suspend fun stageImage(uri: String): String

    suspend fun image(night: Boolean, path: String?)

    suspend fun removeOwnedImage(path: String)

    suspend fun refreshCover()
}

internal interface WelcomeSettingsRepository {
    fun observe(): Flow<WelcomeSettingsSnapshot>

    suspend fun load(): WelcomeSettingsSnapshot

    suspend fun milliseconds(value: Int)

    suspend fun boolean(key: WelcomeSwitch, value: Boolean)

    suspend fun image(night: Boolean, uri: String?)
}

internal class DefaultWelcomeSettingsRepository(
    private val store: WelcomeSettingsStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val gate: Mutex = mutations,
) : WelcomeSettingsRepository {
    override fun observe() = store.changes().map { store.load() }.flowOn(io)

    override suspend fun load() = withContext(io) { store.load() }

    override suspend fun milliseconds(value: Int) = gate.withLock {
        withContext(io + NonCancellable) { store.milliseconds(value.coerceIn(0, 800)) }
    }

    override suspend fun boolean(key: WelcomeSwitch, value: Boolean) = gate.withLock {
        withContext(io + NonCancellable) { store.boolean(key, value) }
    }

    override suspend fun image(night: Boolean, uri: String?) = gate.withLock {
        val path = uri?.let {
            withContext(io) {
                store.stageImage(it).also { currentCoroutineContext().ensureActive() }
            }
        }
        withContext(io + NonCancellable) {
            val previous = store.load().image(night)
            store.image(night, path)
            if (previous.isNotEmpty() && previous != path) store.removeOwnedImage(previous)
            if (path == null) store.refreshCover()
        }
    }

    private companion object {
        val mutations = Mutex()
    }
}
