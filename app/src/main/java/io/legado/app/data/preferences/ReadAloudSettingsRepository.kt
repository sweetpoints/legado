package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getBooleanCompat
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ReadAloudSwitch(val key: String) {
    IgnoreAudioFocus(PreferKey.ignoreAudioFocus),
    PauseDuringCalls(PreferKey.pauseReadAloudWhilePhoneCalls),
    WakeLock(PreferKey.readAloudWakeLock),
    MediaButtonNext("mediaButtonPerNext"),
    ByPage(PreferKey.readAloudByPage),
    FollowManualPage(PreferKey.readAloudFollowManualPage),
    StreamAudio(PreferKey.streamReadAloudAudio),
}

data class ReadAloudPreferences(
    val switches: Map<ReadAloudSwitch, Boolean>,
    val start: String = "sentence",
) {
    operator fun get(setting: ReadAloudSwitch) = switches[setting] ?: false
}

interface ReadAloudSettingsRepository {
    fun load(): ReadAloudPreferences

    fun setSwitch(setting: ReadAloudSwitch, enabled: Boolean)

    fun setStart(mode: String)

    fun observeChanges(onChange: (String?) -> Unit): AutoCloseable

    suspend fun engineName(): String

    fun playbackRunning(): Boolean

    fun notifyPlaybackConfigurationChanged()
}

class PreferenceReadAloudSettingsRepository(context: Context) : ReadAloudSettingsRepository {
    private val context = context.applicationContext
    private val preferences = this.context.defaultSharedPreferences

    override fun load() =
        ReadAloudPreferences(
            ReadAloudSwitch.entries.associateWith { preferences.getBooleanCompat(it.key, false) },
            preferences.getString(PreferKey.readAloudStart, "sentence").takeIf { it == "page" }
                ?: "sentence",
        )

    override fun setSwitch(setting: ReadAloudSwitch, enabled: Boolean) = preferences.edit {
        putBoolean(setting.key, enabled)
    }

    override fun setStart(mode: String) = preferences.edit {
        putString(PreferKey.readAloudStart, mode)
    }

    override fun observeChanges(onChange: (String?) -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            onChange(key)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override suspend fun engineName(): String =
        withContext(Dispatchers.IO) { ReadAloud.getEngineName(context) }

    override fun playbackRunning() = BaseReadAloudService.isRun

    override fun notifyPlaybackConfigurationChanged() {
        postEvent(EventBus.MEDIA_BUTTON, false)
    }
}
