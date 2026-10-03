package io.legado.app.model.cover

import io.legado.app.constant.PreferKey

internal enum class CoverFontSwitch(val key: String, val default: Boolean) {
    Horizontal(PreferKey.coverHorizontal, false),
    Adaptive(PreferKey.coverTitleAdaptive, true),
    Punctuation(PreferKey.coverKeepPunctuation, false),
    CustomSize(PreferKey.coverCustomFontSize, false),
}

internal enum class CoverFontSize(val key: String) {
    TitleLarge(PreferKey.coverTitleLargeSize),
    TitleSmall(PreferKey.coverTitleSmallSize),
    AuthorLarge(PreferKey.coverAuthorLargeSize),
    AuthorSmall(PreferKey.coverAuthorSmallSize),
}

internal data class CoverFontSettingsSnapshot(
    val switches: Map<CoverFontSwitch, Boolean> =
        CoverFontSwitch.entries.associateWith { it.default },
    val sizes: Map<CoverFontSize, Int> = CoverFontSize.entries.associateWith { 100 },
    val fontPath: String = "",
) {
    val customSizesEnabled
        get() = switches.getValue(CoverFontSwitch.CustomSize)
}
