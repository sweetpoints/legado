package io.legado.app.data.preferences

import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MangaReaderSettingsValues(
    val disableZoom: Boolean = false,
    val longPressSave: Boolean = true,
    val disableClickScroll: Boolean = false,
    val horizontal: Boolean = false,
    val rightToLeft: Boolean = false,
    val disableSnap: Boolean = false,
    val disablePageAnimation: Boolean = false,
    val hideChapterTitle: Boolean = false,
    val epaper: Boolean = false,
    val grayscale: Boolean = false,
    val threshold: Int = 128,
    val preloadImages: Int = 10,
    val autoSpeed: Int = 3,
    val useExternalBrowser: Boolean = false,
    val showTitleAddition: Boolean = false,
    val isEInk: Boolean = false,
)

enum class MangaReaderSetting {
    DisableZoom,
    LongPressSave,
    DisableClickScroll,
    Horizontal,
    RightToLeft,
    DisableSnap,
    DisablePageAnimation,
    HideChapterTitle,
    Epaper,
    Grayscale,
    ExternalBrowser,
}

class AppMangaReaderSettingsRepository {
    suspend fun load(): MangaReaderSettingsValues =
        withContext(Dispatchers.IO) {
            MangaReaderSettingsValues(
                disableZoom = AppConfig.disableMangaScale,
                longPressSave = AppConfig.mangaLongClickSaveImage,
                disableClickScroll = AppConfig.disableClickScroll,
                horizontal = AppConfig.enableMangaHorizontalScroll,
                rightToLeft = AppConfig.mangaRightToLeft,
                disableSnap = AppConfig.disableHorizontalPageSnap,
                disablePageAnimation = AppConfig.disableMangaPageAnim,
                hideChapterTitle = AppConfig.hideMangaTitle,
                epaper = AppConfig.enableMangaEInk,
                grayscale = AppConfig.enableMangaGray,
                threshold = AppConfig.mangaEInkThreshold,
                preloadImages = AppConfig.mangaPreDownloadNum,
                autoSpeed = AppConfig.mangaAutoPageSpeed,
                useExternalBrowser = AppConfig.readUrlInBrowser,
                showTitleAddition = AppConfig.showReadTitleBarAddition,
                isEInk = AppConfig.isEInkMode,
            )
        }

    suspend fun set(setting: MangaReaderSetting, value: Boolean): MangaReaderSettingsValues {
        withContext(Dispatchers.IO) {
            when (setting) {
                MangaReaderSetting.DisableZoom -> AppConfig.disableMangaScale = value
                MangaReaderSetting.LongPressSave -> AppConfig.mangaLongClickSaveImage = value
                MangaReaderSetting.DisableClickScroll -> AppConfig.disableClickScroll = value
                MangaReaderSetting.Horizontal -> AppConfig.enableMangaHorizontalScroll = value
                MangaReaderSetting.RightToLeft -> AppConfig.mangaRightToLeft = value
                MangaReaderSetting.DisableSnap -> AppConfig.disableHorizontalPageSnap = value
                MangaReaderSetting.DisablePageAnimation -> AppConfig.disableMangaPageAnim = value
                MangaReaderSetting.HideChapterTitle -> AppConfig.hideMangaTitle = value
                MangaReaderSetting.ExternalBrowser -> AppConfig.readUrlInBrowser = value
                MangaReaderSetting.Epaper -> {
                    AppConfig.enableMangaEInk = value
                    AppConfig.enableMangaGray = false
                }
                MangaReaderSetting.Grayscale -> {
                    AppConfig.enableMangaGray = value
                    AppConfig.enableMangaEInk = false
                }
            }
        }
        return load()
    }

    suspend fun setPreload(value: Int): MangaReaderSettingsValues {
        withContext(Dispatchers.IO) { AppConfig.mangaPreDownloadNum = value.coerceIn(0, 9999) }
        return load()
    }

    suspend fun setAutoSpeed(value: Int): MangaReaderSettingsValues {
        withContext(Dispatchers.IO) { AppConfig.mangaAutoPageSpeed = value.coerceIn(1, 9999) }
        return load()
    }
}
