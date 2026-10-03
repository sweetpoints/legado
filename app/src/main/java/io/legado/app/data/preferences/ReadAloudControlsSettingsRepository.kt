package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.constant.PreferKey
import io.legado.app.ui.book.read.readAloudControlWidth
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getBooleanCompat

enum class ReadAloudControlsToggle(val key: String, val defaultValue: Boolean) {
    Realtime(PreferKey.readAloudControlsRealtime, true),
    Pause(PreferKey.readAloudControlsPause, true),
    Position(PreferKey.readAloudControlsPosition, true),
    AutoHide(PreferKey.readAloudControlsAutoHide, false),
    Drag(PreferKey.readAloudControlsDrag, false),
    Dock(PreferKey.readAloudControlsDock, false),
}

enum class ReadAloudControlsNumber(
    val key: String,
    val minimum: Int,
    val maximum: Int,
    val increment: Int,
    val defaultValue: Int,
) {
    Width(PreferKey.readAloudControlsWidth, 85, 432, 1, 288),
    Opacity(PreferKey.readAloudControlsOpacity, 0, 100, 5, 90),
    Threshold(PreferKey.readAloudControlsThreshold, 10, 500, 10, 100),
}

data class ReadAloudControlsSettings(
    val toggles: Map<ReadAloudControlsToggle, Boolean>,
    val numbers: Map<ReadAloudControlsNumber, Int>,
) {
    operator fun get(toggle: ReadAloudControlsToggle) = toggles[toggle] ?: toggle.defaultValue

    operator fun get(number: ReadAloudControlsNumber) = numbers[number] ?: number.defaultValue
}

interface ReadAloudControlsSettingsRepository {
    fun load(): ReadAloudControlsSettings

    fun setToggle(setting: ReadAloudControlsToggle, enabled: Boolean)

    fun setNumber(setting: ReadAloudControlsNumber, value: Int)

    fun observe(onChange: () -> Unit): AutoCloseable
}

class PreferenceReadAloudControlsSettingsRepository(context: Context) :
    ReadAloudControlsSettingsRepository {
    private val preferences = context.applicationContext.defaultSharedPreferences

    init {
        // Capture the legacy height before assigning defaults, as the former Preference fragment
        // did.
        val width = readAloudControlWidth(preferences)
        preferences.edit {
            ReadAloudControlsToggle.entries.forEach {
                if (!preferences.contains(it.key)) putBoolean(it.key, it.defaultValue)
            }
            putInt(ReadAloudControlsNumber.Width.key, width)
            ReadAloudControlsNumber.entries
                .filterNot { it == ReadAloudControlsNumber.Width }
                .forEach {
                    val value =
                        preferences.getInt(it.key, it.defaultValue).coerceIn(it.minimum, it.maximum)
                    if (
                        !preferences.contains(it.key) ||
                            preferences.getInt(it.key, it.defaultValue) != value
                    )
                        putInt(it.key, value)
                }
        }
    }

    override fun load() =
        ReadAloudControlsSettings(
            ReadAloudControlsToggle.entries.associateWith {
                preferences.getBooleanCompat(it.key, it.defaultValue)
            },
            ReadAloudControlsNumber.entries.associateWith {
                preferences.getInt(it.key, it.defaultValue).coerceIn(it.minimum, it.maximum)
            },
        )

    override fun setToggle(setting: ReadAloudControlsToggle, enabled: Boolean) = preferences.edit {
        putBoolean(setting.key, enabled)
    }

    override fun setNumber(setting: ReadAloudControlsNumber, value: Int) = preferences.edit {
        putInt(setting.key, value.coerceIn(setting.minimum, setting.maximum))
    }

    override fun observe(onChange: () -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("readAloudControls")) onChange()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
