package io.legado.app.data.preferences

import io.legado.app.model.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface ThemeSettingsStore {
    fun changes(): Flow<Unit>
    suspend fun load(): ThemeSettingsSnapshot
    suspend fun boolean(key: ThemeSwitch, value: Boolean)
    suspend fun color(key: ThemeColor, value: Int)
    suspend fun launcher(value: String)
    suspend fun elevation(value: Int)
    suspend fun font(value: Int)
    suspend fun toggleNight()
    suspend fun saveTheme(night: Boolean, name: String)
    suspend fun stageImage(night: Boolean, uri: String): String
    suspend fun image(night: Boolean, path: String?)
}
/** Platform methods run on Main; implementations retain only the application context. */
internal interface ThemeSettingsPlatform {
    suspend fun apply(night: Boolean?)
    fun recreate()
    fun launcher(value: String)
    fun follow(enabled: Boolean, auto: Boolean): Boolean
    fun manualColor()
}
internal interface ThemeSettingsRepository {
    fun observe(): Flow<ThemeSettingsSnapshot>
    suspend fun load(): ThemeSettingsSnapshot
    suspend fun boolean(key: ThemeSwitch, value: Boolean)
    suspend fun color(key: ThemeColor, value: Int)
    suspend fun launcher(value: String)
    suspend fun elevation(value: Int?)
    suspend fun font(value: Int?)
    suspend fun toggleNight()
    suspend fun saveTheme(night: Boolean, name: String)
    suspend fun image(night: Boolean, uri: String?)
    suspend fun refreshTheme(night: Boolean)
}
internal class DefaultThemeSettingsRepository(private val store: ThemeSettingsStore, private val platform: ThemeSettingsPlatform,
    private val io: CoroutineDispatcher = Dispatchers.IO, private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val gate: Mutex = mutationGate) : ThemeSettingsRepository {
    override fun observe() = store.changes().map { store.load() }.flowOn(io)
    override suspend fun load() = withContext(io) { store.load() }
    private suspend fun mutate(action: suspend () -> Unit) = gate.withLock {
        withContext(io + NonCancellable) { action() }
    }
    override suspend fun boolean(key: ThemeSwitch, value: Boolean) = mutate {
        val current = store.load()
        when (key) {
            ThemeSwitch.WallpaperFollow -> {
                checkWallpaper(current)
                if (!withContext(main) { platform.follow(value, current.switches.getValue(ThemeSwitch.WallpaperAuto)) }) unavailable()
                store.boolean(key, value)
            }
            ThemeSwitch.WallpaperAuto -> {
                checkWallpaper(current)
                if (current.switches.getValue(ThemeSwitch.WallpaperFollow) && !withContext(main) { platform.follow(true, value) }) unavailable()
                store.boolean(key, value)
            }
            else -> { store.boolean(key, value); withContext(main) { if (key.night == null) platform.recreate() else platform.apply(key.night) } }
        }
    }
    override suspend fun color(key: ThemeColor, value: Int) = mutate {
        val opaque = value or 0xff000000.toInt()
        if (key.background && !validThemeBackground(key.night, opaque)) throw ThemeSettingsException(if (key.night) ThemeSettingsProblem.NightTooLight else ThemeSettingsProblem.DayTooDark)
        store.color(key, opaque)
        withContext(main) { platform.manualColor(); platform.apply(key.night) }
    }
    override suspend fun launcher(value: String) = mutate {
        check(store.load().launcherAvailable) { "Launcher icon unavailable" }
        store.launcher(value); withContext(main) { platform.launcher(value) }
    }
    override suspend fun elevation(value: Int?) = mutate { store.elevation(value?.coerceIn(0, 32) ?: store.load().defaultElevation); withContext(main) { platform.recreate() } }
    override suspend fun font(value: Int?) = mutate { store.font(value?.coerceIn(8, 16) ?: 0); withContext(main) { platform.recreate() } }
    override suspend fun toggleNight() = mutate { store.toggleNight(); withContext(main) { platform.apply(null) } }
    override suspend fun saveTheme(night: Boolean, name: String) = mutate { store.saveTheme(night, name) }
    override suspend fun image(night: Boolean, uri: String?) = gate.withLock {
        // Download/copy can be canceled; only a complete file becomes the preference's new value.
        val path = uri?.let { withContext(io) { store.stageImage(night, it).also { currentCoroutineContext().ensureActive() } } }
        withContext(io + NonCancellable) { store.image(night, path); withContext(main) { platform.apply(night) } }
    }
    override suspend fun refreshTheme(night: Boolean) { withContext(main) { platform.apply(night) } }
    private fun checkWallpaper(value: ThemeSettingsSnapshot) { if (!value.wallpaperAvailable) unavailable() }
    private fun unavailable(): Nothing = throw ThemeSettingsException(ThemeSettingsProblem.WallpaperUnavailable)
    private companion object { val mutationGate = Mutex() }
}
