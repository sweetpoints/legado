package io.legado.app.model.cover

import io.legado.app.constant.PreferKey

internal enum class CoverSettingSwitch(val key: String, val default: Boolean, val refresh: Boolean) {
    Wifi(PreferKey.loadCoverOnlyWifi, false, false), Default(PreferKey.useDefaultCover, false, true),
    DayName(PreferKey.coverShowName, true, true), DayAuthor(PreferKey.coverShowAuthor, true, true),
    NightName(PreferKey.coverShowNameN, true, true), NightAuthor(PreferKey.coverShowAuthorN, true, true)
}
internal enum class CoverSettingImage(val key: String) {
    Day(PreferKey.defaultCover), Night(PreferKey.defaultCoverDark), RecordDay(PreferKey.readRecordCover), RecordNight(PreferKey.readRecordCoverDark)
}
internal data class CoverSettingsSnapshot(
    val switches: Map<CoverSettingSwitch, Boolean> = CoverSettingSwitch.entries.associateWith { it.default },
    val images: Map<CoverSettingImage, String> = CoverSettingImage.entries.associateWith { "" }) {
    fun enabled(key: CoverSettingSwitch) = when (key) {
        CoverSettingSwitch.DayAuthor -> switches.getValue(CoverSettingSwitch.DayName)
        CoverSettingSwitch.NightAuthor -> switches.getValue(CoverSettingSwitch.NightName)
        else -> true
    }
}
