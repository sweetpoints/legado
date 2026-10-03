package io.legado.app.model.theme

import io.legado.app.constant.PreferKey
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class ThemeSwitch(val key: String, val default: Boolean, val night: Boolean? = null) {
    StatusBar(PreferKey.transparentStatusBar, true),
    NavigationBar(PreferKey.immNavigationBar, true),
    PredictiveBack(PreferKey.disablePredictiveBack, false),
    WallpaperFollow(PreferKey.wallpaperColorFollow, false),
    WallpaperAuto(PreferKey.wallpaperColorAutoUpdate, true),
    DayNavigation(PreferKey.tNavBar, false, false),
    NightNavigation(PreferKey.tNavBarN, false, true),
}

internal enum class ThemeColor(
    val key: String,
    val night: Boolean,
    val background: Boolean = false,
) {
    DayPrimary(PreferKey.cPrimary, false),
    DayAccent(PreferKey.cAccent, false),
    DayBackground(PreferKey.cBackground, false, true),
    DayBottom(PreferKey.cBBackground, false),
    NightPrimary(PreferKey.cNPrimary, true),
    NightAccent(PreferKey.cNAccent, true),
    NightBackground(PreferKey.cNBackground, true, true),
    NightBottom(PreferKey.cNBBackground, true),
}

internal data class ThemeSettingsSnapshot(
    val launcher: String = "ic_launcher",
    val launcherAvailable: Boolean = false,
    val wallpaperAvailable: Boolean = false,
    val switches: Map<ThemeSwitch, Boolean> = ThemeSwitch.entries.associateWith { it.default },
    val colors: Map<ThemeColor, Int> = emptyMap(),
    val dayImage: String = "",
    val nightImage: String = "",
    val night: Boolean = false,
    val elevation: Int = 0,
    val defaultElevation: Int = 0,
    val fontScale: Int = 0,
    val systemFontScale: Float = 1f,
) {
    fun image(night: Boolean) = if (night) nightImage else dayImage

    val effectiveFontScale
        get() = (fontScale / 10f).takeIf { it in .8f..1.6f } ?: systemFontScale

    val fontPicker
        get() = (effectiveFontScale * 10).roundToInt().coerceIn(8, 16)
}

internal enum class ThemeSettingsProblem {
    DayTooDark,
    NightTooLight,
    WallpaperUnavailable,
}

internal class ThemeSettingsException(val problem: ThemeSettingsProblem) :
    IllegalArgumentException(problem.name)

/** AndroidX's sRGB relative luminance; alpha does not affect the legacy background validation. */
internal fun validThemeBackground(night: Boolean, color: Int): Boolean {
    fun channel(shift: Int): Double {
        val value = (color ushr shift and 255) / 255.0
        return if (value < .03928) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    }
    val light = .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0) >= .5
    return light != night
}
