package io.legado.app.data.preferences

import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.utils.postEvent

enum class TipSetting { TitleMode, TitleSize, TitleLineSpacing, TitleBold, TitleColor, TitleNumberSize, TitleNumberSpacing, TitleNumberColor, TitleTop, TitleBottom, HeaderMode, FooterMode, TipSize, TipColor, DividerColor, SplitTitle }
enum class TipTemplateSlot { HeaderLeft, HeaderMiddle, HeaderRight, FooterLeft, FooterMiddle, FooterRight }

data class TipSettingsSnapshot(
    val values: Map<TipSetting, Int>,
    val titleFont: String = "",
    val templates: Map<TipTemplateSlot, String> = emptyMap(),
    val effectiveTitleColor: Int = 0xff000000.toInt(),
    val effectiveNumberColor: Int = 0xff000000.toInt(),
) {
    operator fun get(setting: TipSetting): Int = values[setting] ?: 0
}

interface TipSettingsRepository {
    fun load(): TipSettingsSnapshot
    fun set(setting: TipSetting, value: Int)
    fun setFont(path: String)
    fun setTemplate(slot: TipTemplateSlot, value: String)
}

internal fun tipSettingEvents(setting: TipSetting): ArrayList<Int> = when (setting) {
    TipSetting.TitleMode, TipSetting.SplitTitle, TipSetting.TitleNumberSpacing -> arrayListOf(5)
    TipSetting.HeaderMode, TipSetting.FooterMode, TipSetting.TipSize, TipSetting.TipColor, TipSetting.DividerColor -> arrayListOf(2)
    else -> arrayListOf(8, 5)
}

internal fun tipTemplateEvents(): ArrayList<Int> = arrayListOf(2, 6)

class AppTipSettingsRepository : TipSettingsRepository {
    override fun load(): TipSettingsSnapshot {
        if (ReadBookConfig.titleMode !in 0..3) ReadBookConfig.titleMode = 0
        return TipSettingsSnapshot(mapOf(
            TipSetting.TitleMode to ReadBookConfig.titleMode,
            TipSetting.TitleSize to ReadBookConfig.titleSize,
            TipSetting.TitleLineSpacing to ReadBookConfig.titleLineSpacingExtra,
            TipSetting.TitleBold to ReadBookConfig.titleBold,
            TipSetting.TitleColor to ReadBookConfig.titleColor,
            TipSetting.TitleNumberSize to ReadBookConfig.titleNumberSize,
            TipSetting.TitleNumberSpacing to ReadBookConfig.titleNumberSpacing,
            TipSetting.TitleNumberColor to ReadBookConfig.titleNumberColor,
            TipSetting.TitleTop to ReadBookConfig.titleTopSpacing,
            TipSetting.TitleBottom to ReadBookConfig.titleBottomSpacing,
            TipSetting.HeaderMode to ReadTipConfig.headerMode,
            TipSetting.FooterMode to ReadTipConfig.footerMode,
            TipSetting.TipSize to ReadTipConfig.tipTextSize,
            TipSetting.TipColor to ReadTipConfig.tipColor,
            TipSetting.DividerColor to ReadTipConfig.tipDividerColor,
            TipSetting.SplitTitle to if (ReadBookConfig.splitChapterTitle) 1 else 0,
        ), ReadBookConfig.titleFont, mapOf(
            TipTemplateSlot.HeaderLeft to ReadTipConfig.effectiveTemplate(ReadTipConfig.tipHeaderLeftTemplate, ReadTipConfig.tipHeaderLeft),
            TipTemplateSlot.HeaderMiddle to ReadTipConfig.effectiveTemplate(ReadTipConfig.tipHeaderMiddleTemplate, ReadTipConfig.tipHeaderMiddle),
            TipTemplateSlot.HeaderRight to ReadTipConfig.effectiveTemplate(ReadTipConfig.tipHeaderRightTemplate, ReadTipConfig.tipHeaderRight),
            TipTemplateSlot.FooterLeft to ReadTipConfig.effectiveTemplate(ReadTipConfig.tipFooterLeftTemplate, ReadTipConfig.tipFooterLeft),
            TipTemplateSlot.FooterMiddle to ReadTipConfig.effectiveTemplate(ReadTipConfig.tipFooterMiddleTemplate, ReadTipConfig.tipFooterMiddle),
            TipTemplateSlot.FooterRight to ReadTipConfig.effectiveTemplate(ReadTipConfig.tipFooterRightTemplate, ReadTipConfig.tipFooterRight),
        ), ReadBookConfig.titleTextColor, ReadBookConfig.titleNumberTextColor)
    }
    override fun set(setting: TipSetting, value: Int) {
        when (setting) {
            TipSetting.TitleMode -> ReadBookConfig.titleMode = value
            TipSetting.TitleSize -> ReadBookConfig.titleSize = value
            TipSetting.TitleLineSpacing -> ReadBookConfig.titleLineSpacingExtra = value
            TipSetting.TitleBold -> ReadBookConfig.titleBold = value
            TipSetting.TitleColor -> ReadBookConfig.titleColor = value
            TipSetting.TitleNumberSize -> ReadBookConfig.titleNumberSize = value
            TipSetting.TitleNumberSpacing -> ReadBookConfig.titleNumberSpacing = value
            TipSetting.TitleNumberColor -> ReadBookConfig.titleNumberColor = value
            TipSetting.TitleTop -> ReadBookConfig.titleTopSpacing = value
            TipSetting.TitleBottom -> ReadBookConfig.titleBottomSpacing = value
            TipSetting.HeaderMode -> ReadTipConfig.headerMode = value
            TipSetting.FooterMode -> ReadTipConfig.footerMode = value
            TipSetting.TipSize -> ReadTipConfig.tipTextSize = value
            TipSetting.TipColor -> ReadTipConfig.tipColor = value
            TipSetting.DividerColor -> ReadTipConfig.tipDividerColor = value
            TipSetting.SplitTitle -> ReadBookConfig.splitChapterTitle = value != 0
        }
        postEvent(EventBus.UP_CONFIG, tipSettingEvents(setting))
    }
    override fun setFont(path: String) {
        ReadBookConfig.titleFont = path
        postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
    }
    override fun setTemplate(slot: TipTemplateSlot, value: String) {
        when (slot) {
            TipTemplateSlot.HeaderLeft -> ReadTipConfig.tipHeaderLeftTemplate = value
            TipTemplateSlot.HeaderMiddle -> ReadTipConfig.tipHeaderMiddleTemplate = value
            TipTemplateSlot.HeaderRight -> ReadTipConfig.tipHeaderRightTemplate = value
            TipTemplateSlot.FooterLeft -> ReadTipConfig.tipFooterLeftTemplate = value
            TipTemplateSlot.FooterMiddle -> ReadTipConfig.tipFooterMiddleTemplate = value
            TipTemplateSlot.FooterRight -> ReadTipConfig.tipFooterRightTemplate = value
        }
        postEvent(EventBus.UP_CONFIG, tipTemplateEvents())
    }
}
