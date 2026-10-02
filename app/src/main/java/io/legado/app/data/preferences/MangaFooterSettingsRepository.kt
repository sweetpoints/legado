package io.legado.app.data.preferences

import com.google.gson.Gson
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.utils.postEvent

/** Immutable draft, isolated from mutable configurations consumed by the reader. */
data class MangaFooterDraft(
    val hideChapterLabel: Boolean = false,
    val hideChapter: Boolean = false,
    val hidePageNumberLabel: Boolean = false,
    val hidePageNumber: Boolean = false,
    val hideProgressRatioLabel: Boolean = false,
    val hideProgressRatio: Boolean = false,
    val footerOrientation: Int = 0,
    val hideFooter: Boolean = false,
    val hideChapterName: Boolean = false,
) {
    fun toConfig() = MangaFooterConfig(hideChapterLabel, hideChapter, hidePageNumberLabel,
        hidePageNumber, hideProgressRatioLabel, hideProgressRatio, footerOrientation,
        hideFooter, hideChapterName)
}

object MangaFooterJson {
    private val gson = Gson()
    fun decode(json: String?): MangaFooterDraft = runCatching {
        val config = gson.fromJson(json, MangaFooterConfig::class.java) ?: return@runCatching MangaFooterDraft()
        MangaFooterDraft(config.hideChapterLabel, config.hideChapter, config.hidePageNumberLabel,
            config.hidePageNumber, config.hideProgressRatioLabel, config.hideProgressRatio,
            config.footerOrientation, config.hideFooter, config.hideChapterName)
    }.getOrDefault(MangaFooterDraft())
    fun encode(draft: MangaFooterDraft): String = gson.toJson(draft.toConfig())
}

interface MangaFooterSettingsRepository {
    fun load(): MangaFooterDraft
    fun preview(draft: MangaFooterDraft)
    fun save(draft: MangaFooterDraft)
}

class AppMangaFooterSettingsRepository : MangaFooterSettingsRepository {
    override fun load() = MangaFooterJson.decode(AppConfig.mangaFooterConfig)
    override fun preview(draft: MangaFooterDraft) {
        // Event subscribers may mutate this object; never give them the UI's draft.
        postEvent(EventBus.UP_MANGA_CONFIG, draft.toConfig())
    }
    override fun save(draft: MangaFooterDraft) {
        AppConfig.mangaFooterConfig = MangaFooterJson.encode(draft)
    }
}
