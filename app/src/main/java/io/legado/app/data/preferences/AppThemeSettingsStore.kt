package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.http.*
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.theme.*
import io.legado.app.utils.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.io.InputStream

internal class AppThemeSettingsStore(context: Context) : ThemeSettingsStore {
    private val application = context.applicationContext
    private val preferences by lazy { application.defaultSharedPreferences }
    override fun changes() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    override suspend fun load(): ThemeSettingsSnapshot {
        val defaults = mapOf(ThemeColor.DayPrimary to R.color.md_brown_500, ThemeColor.DayAccent to R.color.md_red_600,
            ThemeColor.DayBackground to R.color.md_grey_100, ThemeColor.DayBottom to R.color.md_grey_200,
            ThemeColor.NightPrimary to R.color.md_blue_grey_600, ThemeColor.NightAccent to R.color.md_deep_orange_800,
            ThemeColor.NightBackground to R.color.md_grey_900, ThemeColor.NightBottom to R.color.md_grey_850)
        val values = preferences.all
        return ThemeSettingsSnapshot(values[PreferKey.launcherIcon] as? String ?: "ic_launcher", Build.VERSION.SDK_INT >= 26,
            Build.VERSION.SDK_INT >= 31, ThemeSwitch.entries.associateWith { values[it.key] as? Boolean ?: it.default },
            defaults.mapValues { (key, resource) -> values[key.key] as? Int ?: application.getCompatColor(resource) },
            values[PreferKey.bgImage] as? String ?: "", values[PreferKey.bgImageN] as? String ?: "", AppConfig.isNightTheme,
            if (AppConfig.isEInkMode) 0 else values[PreferKey.barElevation] as? Int ?: AppConst.sysElevation,
            AppConst.sysElevation, values[PreferKey.fontScale] as? Int ?: 0, sysConfiguration.fontScale)
    }
    private fun write(editor: SharedPreferences.Editor) { check(editor.commit()) { "Unable to save theme settings" } }
    override suspend fun boolean(key: ThemeSwitch, value: Boolean) = write(preferences.edit().putBoolean(key.key, value))
    override suspend fun color(key: ThemeColor, value: Int) = write(preferences.edit().putInt(key.key, value))
    override suspend fun launcher(value: String) = write(preferences.edit().putString(PreferKey.launcherIcon, value))
    override suspend fun elevation(value: Int) = write(preferences.edit().putInt(PreferKey.barElevation, value))
    override suspend fun font(value: Int) = write(preferences.edit().putInt(PreferKey.fontScale, value))
    override suspend fun toggleNight() = write(preferences.edit().putString(PreferKey.themeMode, if (AppConfig.isNightTheme) "1" else "2"))
    override suspend fun saveTheme(night: Boolean, name: String) {
        if (night) ThemeConfig.saveNightTheme(application, name) else ThemeConfig.saveDayTheme(application, name)
    }
    override suspend fun image(night: Boolean, path: String?) {
        val editor = preferences.edit(); val key = if (night) PreferKey.bgImageN else PreferKey.bgImage
        write(if (path == null) editor.remove(key) else editor.putString(key, path))
    }
    override suspend fun stageImage(night: Boolean, uri: String): String {
        val key = if (night) PreferKey.bgImageN else PreferKey.bgImage
        val value = Uri.parse(uri)
        if (value.scheme?.lowercase() in listOf("http", "https")) {
            val analyze = AnalyzeUrl(uri, coroutineContext = currentCoroutineContext())
            val url = analyze.urlNoQuery
            val response = okHttpClient.newCallResponse(0) { addHeaders(analyze.headerMap); url(url) }
            response.use {
                val type = it.header("Content-Type") ?: "image/jpeg"
                val extension = when { type.contains("png", true) -> "png"; type.contains("gif", true) -> "gif"; type.contains("webp", true) -> "webp"; else -> "jpg" }
                val suffix = if (url.contains(".9.png", true)) ".9.png" else ".$extension"
                return it.body.byteStream().use { input -> install(key, MD5Utils.md5Encode(url) + suffix, input) }
            }
        }
        val document = FileDoc.fromUri(value, false)
        val suffix = if (document.name.contains(".9.png", true)) ".9.png" else "." + document.name.substringAfterLast('.')
        val name = document.openInputStream().getOrThrow().use { MD5Utils.md5Encode(it) + suffix }
        return document.openInputStream().getOrThrow().use { install(key, name, it) }
    }
    private suspend fun install(key: String, name: String, input: InputStream): String {
        val folder = File(application.externalFiles, key); check(folder.isDirectory || folder.mkdirs()) { "Unable to create background directory" }
        val file = File(folder, name); val atomic = AtomicFile(file); val output = atomic.startWrite()
        try {
            val buffer = ByteArray(8192)
            while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
            currentCoroutineContext().ensureActive(); atomic.finishWrite(output)
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
        return file.absolutePath
    }
}
