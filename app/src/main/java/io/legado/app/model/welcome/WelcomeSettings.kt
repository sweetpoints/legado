package io.legado.app.model.welcome

import io.legado.app.constant.PreferKey

internal enum class WelcomeSwitch(val key: String, val default: Boolean) {
    Custom(PreferKey.customWelcome, false), DayText(PreferKey.welcomeShowText, true), DayIcon(PreferKey.welcomeShowIcon, true),
    NightText(PreferKey.welcomeShowTextDark, true), NightIcon(PreferKey.welcomeShowIconDark, true)
}
internal data class WelcomeSettingsSnapshot(val milliseconds: Int = 500,
    val switches: Map<WelcomeSwitch, Boolean> = WelcomeSwitch.entries.associateWith { it.default }, val dayImage: String = "", val nightImage: String = "") {
    fun image(night: Boolean) = if (night) nightImage else dayImage
}
