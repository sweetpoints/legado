package io.legado.app.data.preferences

import io.legado.app.model.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.Executors

class ThemeSettingsRepositoryTest {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "theme-test-io") }.asCoroutineDispatcher()
    private val main = Executors.newSingleThreadExecutor { Thread(it, "theme-test-main") }.asCoroutineDispatcher()
    @After fun close() { io.close(); main.close() }
    private class Store : ThemeSettingsStore {
        var value = ThemeSettingsSnapshot(launcherAvailable = true, wallpaperAvailable = true, defaultElevation = 4)
        val effects = mutableListOf<String>(); val threads = mutableListOf<Thread>()
        private fun call(event: String) { effects += event; threads += Thread.currentThread() }
        override fun changes() = flowOf(Unit)
        override suspend fun load(): ThemeSettingsSnapshot { call("load"); return value }
        override suspend fun boolean(key: ThemeSwitch, value: Boolean) { call("bool:${key.name}:$value"); this.value = this.value.copy(switches = this.value.switches + (key to value)) }
        override suspend fun color(key: ThemeColor, value: Int) { call("color:${key.name}:$value"); this.value = this.value.copy(colors = this.value.colors + (key to value)) }
        override suspend fun launcher(value: String) { call("launcher:$value") }
        override suspend fun elevation(value: Int) { call("elevation:$value") }
        override suspend fun font(value: Int) { call("font:$value") }
        override suspend fun toggleNight() { call("night") }
        override suspend fun saveTheme(night: Boolean, name: String) { call("save:$night:$name") }
        override suspend fun stageImage(night: Boolean, uri: String): String { call("stage:$night:$uri"); if (uri == "bad") error("image failed"); return "installed/$uri" }
        override suspend fun image(night: Boolean, path: String?) { call("image:$night:$path") }
    }
    private class Platform : ThemeSettingsPlatform {
        val effects = mutableListOf<String>(); val threads = mutableListOf<Thread>(); var available = true
        private fun call(value: String) { effects += value; threads += Thread.currentThread() }
        override suspend fun apply(night: Boolean?) { call("apply:$night") }
        override fun recreate() { call("recreate") }
        override fun launcher(value: String) { call("launcher:$value") }
        override fun follow(enabled: Boolean, auto: Boolean): Boolean { call("follow:$enabled:$auto"); return available }
        override fun manualColor() { call("manual") }
    }
    @Test fun preferencesAndBackgroundWorkOnIoWhileAllPlatformTransitionsRunOnMain() = runBlocking {
        val store = Store(); val platform = Platform(); val repo = DefaultThemeSettingsRepository(store, platform, io, main)
        val ioThread = withContext(io) { Thread.currentThread() }; val mainThread = withContext(main) { Thread.currentThread() }
        repo.observe().first(); repo.boolean(ThemeSwitch.StatusBar, false); repo.launcher("Launcher2")
        repo.image(true, "content:test"); repo.toggleNight(); repo.saveTheme(false, "full theme name")
        assertTrue(store.threads.isNotEmpty()); assertTrue(store.threads.all { it === ioThread })
        assertTrue(platform.threads.isNotEmpty()); assertTrue(platform.threads.all { it === mainThread })
        assertEquals(listOf("recreate", "launcher:Launcher2", "apply:true", "apply:null"), platform.effects)
        assertTrue(store.effects.contains("save:false:full theme name"))
    }
    @Test fun fontAndElevationUseEffectiveSystemDefaultsAndClampOnlyExplicitValues() = runBlocking {
        val store = Store(); val platform = Platform(); val repo = DefaultThemeSettingsRepository(store, platform, io, main)
        repo.font(99); repo.font(-1); repo.font(null); repo.elevation(99); repo.elevation(-1); repo.elevation(null)
        assertEquals(listOf("font:16", "font:8", "font:0", "elevation:32", "elevation:0", "load", "elevation:4"), store.effects)
        assertEquals(List(6) { "recreate" }, platform.effects)
        assertEquals(13, ThemeSettingsSnapshot(systemFontScale = 1.26f).fontPicker)
        assertEquals(15, ThemeSettingsSnapshot(fontScale = 15, systemFontScale = 1f).fontPicker)
        assertEquals(8, ThemeSettingsSnapshot(fontScale = 99, systemFontScale = .5f).fontPicker)
        assertEquals(16, ThemeSettingsSnapshot(fontScale = 0, systemFontScale = 2f).fontPicker)
    }
    @Test fun dayAndNightBackgroundValidationRejectsBeforeWriteAndAcceptedColorsBecomeOpaque() = runBlocking {
        val store = Store(); val platform = Platform(); val repo = DefaultThemeSettingsRepository(store, platform, io, main)
        try { repo.color(ThemeColor.DayBackground, 0xff000000.toInt()); fail() } catch (error: ThemeSettingsException) { assertEquals(ThemeSettingsProblem.DayTooDark, error.problem) }
        try { repo.color(ThemeColor.NightBackground, -1); fail() } catch (error: ThemeSettingsException) { assertEquals(ThemeSettingsProblem.NightTooLight, error.problem) }
        assertTrue(store.effects.isEmpty()); assertTrue(platform.effects.isEmpty())
        repo.color(ThemeColor.DayBackground, 0x00ffffff); repo.color(ThemeColor.NightAccent, 0x00112233)
        assertEquals(-1, store.value.colors.getValue(ThemeColor.DayBackground)); assertEquals(0xff112233.toInt(), store.value.colors.getValue(ThemeColor.NightAccent))
        assertEquals(listOf("manual", "apply:false", "manual", "apply:true"), platform.effects)
    }
    @Test fun wallpaperFailureDoesNotPersistAndAutoUpdateChecksLatestFollowSetting() = runBlocking {
        val store = Store(); val platform = Platform().apply { available = false }; val repo = DefaultThemeSettingsRepository(store, platform, io, main)
        try { repo.boolean(ThemeSwitch.WallpaperFollow, true); fail() } catch (error: ThemeSettingsException) { assertEquals(ThemeSettingsProblem.WallpaperUnavailable, error.problem) }
        assertFalse(store.value.switches.getValue(ThemeSwitch.WallpaperFollow))
        assertFalse(store.effects.any { it.startsWith("bool:") })
        platform.available = true; repo.boolean(ThemeSwitch.WallpaperFollow, true); repo.boolean(ThemeSwitch.WallpaperAuto, false)
        assertEquals(listOf("follow:true:true", "follow:true:true", "follow:true:false"), platform.effects)
        assertFalse(store.value.switches.getValue(ThemeSwitch.WallpaperAuto))
    }
    @Test fun unavailableWallpaperNeverCallsPlatformAndFailureDoesNotReplaceBackground() = runBlocking {
        val store = Store().apply { value = value.copy(wallpaperAvailable = false) }; val platform = Platform(); val repo = DefaultThemeSettingsRepository(store, platform, io, main)
        try { repo.boolean(ThemeSwitch.WallpaperFollow, true); fail() } catch (_: ThemeSettingsException) { }
        assertTrue(platform.effects.isEmpty())
        try { repo.image(false, "bad"); fail() } catch (_: IllegalStateException) { }
        assertFalse(store.effects.any { it.startsWith("image:") }); assertTrue(platform.effects.isEmpty())
        repo.image(false, null); assertTrue(store.effects.contains("image:false:null")); assertEquals(listOf("apply:false"), platform.effects)
    }
    @Test fun wallpaperAutoWithoutFollowDoesNotReadWallpaperAndSavingThemeDoesNotApplyIt() = runBlocking {
        val store = Store(); val platform = Platform(); val repo = DefaultThemeSettingsRepository(store, platform, io, main)
        repo.boolean(ThemeSwitch.WallpaperAuto, false); repo.saveTheme(true, "  name preserved  ")
        assertEquals(listOf("load", "bool:WallpaperAuto:false", "save:true:  name preserved  "), store.effects)
        assertTrue(platform.effects.isEmpty())
    }
}
