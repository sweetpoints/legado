package io.legado.app.data.preferences

import io.legado.app.model.VideoPlay

data class VideoSettings(
    val autoPlay: Boolean = true,
    val defaultFloatWindow: Boolean = false,
    val startFull: Boolean = false,
    val fullBottomProgress: Boolean = true,
    val pressSpeed: Int = 30,
)

enum class VideoSetting {
    AutoPlay,
    DefaultFloatWindow,
    StartFull,
    FullBottomProgress,
}

interface VideoSettingsRepository {
    fun load(): VideoSettings

    fun setEnabled(setting: VideoSetting, enabled: Boolean)

    fun setPressSpeed(value: Int)
}

class AppVideoSettingsRepository : VideoSettingsRepository {
    override fun load() =
        VideoSettings(
            VideoPlay.autoPlay,
            VideoPlay.defaultFloatWindow,
            VideoPlay.startFull,
            VideoPlay.fullBottomProgressBar,
            VideoPlay.longPressSpeed.coerceIn(5, 60),
        )

    override fun setEnabled(setting: VideoSetting, enabled: Boolean) {
        when (setting) {
            VideoSetting.AutoPlay -> VideoPlay.autoPlay = enabled
            VideoSetting.DefaultFloatWindow -> VideoPlay.defaultFloatWindow = enabled
            VideoSetting.StartFull -> VideoPlay.startFull = enabled
            VideoSetting.FullBottomProgress -> VideoPlay.fullBottomProgressBar = enabled
        }
    }

    override fun setPressSpeed(value: Int) {
        VideoPlay.longPressSpeed = value.coerceIn(5, 60)
    }
}
