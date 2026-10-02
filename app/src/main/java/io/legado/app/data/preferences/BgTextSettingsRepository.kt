package io.legado.app.data.preferences

import android.content.Context
import androidx.core.graphics.toColorInt
import io.legado.app.constant.EventBus
import io.legado.app.help.DefaultData
import io.legado.app.help.book.ResourceThemeGeneration
import io.legado.app.help.book.isImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.parseReadConfigObject
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.data.repository.ReaderBackgroundExportSnapshot
import io.legado.app.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class BgTextSetting { Name, DarkStatus, UnderlineMode, UnderlineWidth, UnderlineDistance, UnderlineBody, UnderlineTitle,
    Alpha, TextColor, AccentColor, BackgroundColor, ReviewColor, ReviewScale, ReviewSvg, AssetBackground, FileBackground }
enum class BgTextColor { Text, Background, Accent, Review, Underline }
data class BgTextTemplate(val name: String, val svg: String)
data class BgTextPreset(val name: String, val configuration: String)
data class BgTextSettingsSnapshot(val context: String, val name: String, val imageBook: Boolean = false,
    val darkStatus: Boolean = true, val alpha: Int = 100, val configuration: String = "{}",
    val underlineMode: Int = 0, val underlineWidth: Int = 2, val underlineDistance: Int = 8,
    val underlineBody: Boolean = true, val underlineTitle: Boolean = true, val underlineColor: Int = 0,
    val textColor: Int = 0, val accentColor: Int = 0, val backgroundColor: Int = 0xff015a86.toInt(),
    val reviewColor: Int = 0, val effectiveReviewColor: Int = 0, val reviewScale: Int = 100, val reviewSvg: String = "",
    val templates: List<BgTextTemplate> = emptyList()) {
    fun color(color: BgTextColor): Int = when (color) {
        BgTextColor.Text -> textColor; BgTextColor.Background -> backgroundColor; BgTextColor.Accent -> accentColor
        BgTextColor.Review -> effectiveReviewColor; BgTextColor.Underline -> underlineColor
    }
}
data class BgTextUpdate(val codes: List<Int> = emptyList(), val systemUi: Boolean = false,
    val reviewCache: Boolean = false, val actionBar: Boolean = false)
internal fun bgTextUpdate(setting: BgTextSetting): BgTextUpdate = when (setting) {
    BgTextSetting.Name -> BgTextUpdate()
    BgTextSetting.DarkStatus -> BgTextUpdate(systemUi = true)
    BgTextSetting.Alpha -> BgTextUpdate(codes = listOf(3))
    BgTextSetting.UnderlineMode, BgTextSetting.UnderlineWidth, BgTextSetting.UnderlineDistance,
    BgTextSetting.UnderlineBody, BgTextSetting.UnderlineTitle -> BgTextUpdate(codes = listOf(6, 9, 11))
    BgTextSetting.TextColor, BgTextSetting.AccentColor -> BgTextUpdate(codes = listOf(2, 6, 9, 11), actionBar = true)
    BgTextSetting.BackgroundColor -> BgTextUpdate(codes = listOf(1), actionBar = true)
    BgTextSetting.ReviewColor -> BgTextUpdate(codes = listOf(8, 9, 11))
    BgTextSetting.ReviewScale, BgTextSetting.ReviewSvg -> BgTextUpdate(codes = listOf(9, 11), reviewCache = true)
    BgTextSetting.AssetBackground, BgTextSetting.FileBackground -> BgTextUpdate(codes = listOf(1))
}
interface BgTextSettingsRepository {
    fun load(): BgTextSettingsSnapshot
    fun checkpoint(): String
    fun restore(checkpoint: String)
    fun set(setting: BgTextSetting, value: String): BgTextUpdate
    fun color(color: BgTextColor, value: Int): BgTextUpdate
    fun replace(configuration: String, restoreDefault: Boolean): BgTextUpdate
    fun delete(): Boolean
    fun putTemplate(name: String, svg: String)
    fun removeTemplate(svg: String)
    suspend fun assets(): List<String>
    suspend fun defaults(): List<BgTextPreset>
    suspend fun validSvg(svg: String): Boolean
    fun exportSnapshot(): ReaderBackgroundExportSnapshot
    fun dispatch(update: BgTextUpdate)
    fun save()
}
private data class BgTextCheckpoint(val presets: List<ReadBookConfig.Config>, val shared: ReadBookConfig.Config,
    val selectedRead: Int, val selectedComic: Int, val shareLayout: Boolean)
class AppBgTextSettingsRepository(context: Context) : BgTextSettingsRepository {
    private val context = context.applicationContext
    override fun load(): BgTextSettingsSnapshot {
        val config = ReadBookConfig.durConfig
        return BgTextSettingsSnapshot("${ReadBookConfig.isComic}:${ReadBookConfig.styleSelect}:${ReadBookConfig.shareLayout}:${AppConfig.isEInkMode}:${AppConfig.isNightTheme}",
            config.name, ReadBook.book?.isImage == true, config.curStatusIconDark(), ReadBookConfig.bgAlpha, GSON.toJson(config),
            ReadBookConfig.underlineMode, (ReadBookConfig.underlineWidth * 2f).roundToInt().coerceIn(0, 20),
            (ReadBookConfig.underlineDistance * 2f).roundToInt().coerceIn(0, 60), ReadBookConfig.underlineBodyEnabled,
            ReadBookConfig.underlineTitleEnabled, if (ReadBookConfig.underlineColorSet) ReadBookConfig.underlineColor else ReadBookConfig.textColor,
            config.curTextColor(), config.curTextAccentColor(), if (config.curBgType() == 0) runCatching { config.curBgStr().toColorInt() }.getOrDefault(0xff015a86.toInt()) else 0xff015a86.toInt(),
            ReadBookConfig.reviewIconColor, ReadBookConfig.reviewIconColor.takeIf { it != 0 } ?: ChapterProvider.reviewPaint.color,
            ReadBookConfig.reviewIconScale, ReadBookConfig.reviewIconSvg, config.reviewIconSvgTemplates.map { BgTextTemplate(it.name, it.svg) })
    }
    override fun checkpoint(): String = GSON.toJson(BgTextCheckpoint(ReadBookConfig.configList.toList(), ReadBookConfig.shareConfig,
        ReadBookConfig.readStyleSelect, ReadBookConfig.comicStyleSelect, ReadBookConfig.shareLayout))
    override fun restore(checkpoint: String) {
        val value = GSON.fromJsonObject<BgTextCheckpoint>(checkpoint).getOrNull() ?: return
        if (value.presets.size < 5 || value.selectedRead !in value.presets.indices || value.selectedComic !in value.presets.indices) return
        ReadBookConfig.configList.clear(); ReadBookConfig.configList.addAll(value.presets); ReadBookConfig.shareConfig = value.shared
        ReadBookConfig.readStyleSelect = value.selectedRead; ReadBookConfig.comicStyleSelect = value.selectedComic
        ReadBookConfig.shareLayout = value.shareLayout; ResourceThemeGeneration.changed()
    }
    override fun set(setting: BgTextSetting, value: String): BgTextUpdate {
        when (setting) {
            BgTextSetting.Name -> ReadBookConfig.durConfig.name = value
            BgTextSetting.DarkStatus -> ReadBookConfig.durConfig.setCurStatusIconDark(value.toBooleanStrict())
            BgTextSetting.UnderlineMode -> ReadBookConfig.underlineMode = value.toInt().coerceIn(0, 6)
            BgTextSetting.UnderlineWidth -> ReadBookConfig.underlineWidth = value.toInt().coerceIn(0, 20) / 2f
            BgTextSetting.UnderlineDistance -> ReadBookConfig.underlineDistance = value.toInt().coerceIn(0, 60) / 2f
            BgTextSetting.UnderlineBody -> ReadBookConfig.underlineBodyEnabled = value.toBooleanStrict()
            BgTextSetting.UnderlineTitle -> ReadBookConfig.underlineTitleEnabled = value.toBooleanStrict()
            BgTextSetting.Alpha -> ReadBookConfig.bgAlpha = value.toInt().coerceIn(0, 100)
            BgTextSetting.TextColor -> ReadBookConfig.durConfig.setCurTextColor(value.toInt())
            BgTextSetting.AccentColor -> ReadBookConfig.durConfig.setCurTextAccentColor(value.toInt())
            BgTextSetting.BackgroundColor -> ReadBookConfig.durConfig.setCurBg(0, "#${value.toInt().hexString}")
            BgTextSetting.ReviewColor -> ReadBookConfig.reviewIconColor = value.toInt()
            BgTextSetting.ReviewScale -> ReadBookConfig.reviewIconScale = value.toInt().coerceIn(50, 200)
            BgTextSetting.ReviewSvg -> ReadBookConfig.reviewIconSvg = value
            BgTextSetting.AssetBackground -> ReadBookConfig.durConfig.setCurBg(1, value)
            BgTextSetting.FileBackground -> ReadBookConfig.durConfig.setCurBg(2, value)
        }
        return bgTextUpdate(setting)
    }
    override fun color(color: BgTextColor, value: Int): BgTextUpdate = when (color) {
        BgTextColor.Text -> set(BgTextSetting.TextColor, value.toString())
        BgTextColor.Background -> set(BgTextSetting.BackgroundColor, value.toString())
        BgTextColor.Accent -> set(BgTextSetting.AccentColor, value.toString())
        BgTextColor.Review -> set(BgTextSetting.ReviewColor, value.toString())
        BgTextColor.Underline -> { ReadBookConfig.underlineColor = value; BgTextUpdate(codes = listOf(6, 9, 11)) }
    }
    override fun replace(configuration: String, restoreDefault: Boolean): BgTextUpdate {
        ReadBookConfig.durConfig = parseReadConfigObject(configuration).getOrThrow()
        return BgTextUpdate(codes = if (restoreDefault) listOf(1, 2, 5, 13) else listOf(1, 2, 5))
    }
    override fun delete() = ReadBookConfig.deleteDur()
    override fun putTemplate(name: String, svg: String) { ReadBookConfig.durConfig.putReviewIconSvgTemplate(name, svg) }
    override fun removeTemplate(svg: String) { ReadBookConfig.durConfig.removeReviewIconSvgTemplate(svg) }
    override suspend fun assets(): List<String> = withContext(Dispatchers.IO) { context.assets.list("bg")?.toList().orEmpty() }
    override suspend fun defaults(): List<BgTextPreset> = withContext(Dispatchers.IO) { DefaultData.readConfigs.map { BgTextPreset(it.name, GSON.toJson(it)) } }
    override suspend fun validSvg(svg: String): Boolean = withContext(Dispatchers.Default) {
        val resolved = svg.replace("{{count}}", "88")
        val ratio = SvgUtils.getAspectRatioFromSvgText(resolved) ?: return@withContext false
        if (!ChapterProvider.isReviewIconAspectRatioSupported(ratio)) return@withContext false
        val bitmap = SvgUtils.createBitmapFromSvgText(resolved, 48, 48) ?: return@withContext false
        bitmap.recycle(); true
    }
    override fun exportSnapshot() = ReaderBackgroundExportSnapshot(GSON.toJson(ReadBookConfig.getExportConfig()), ReadBookConfig.config.name,
        ReadBookConfig.textFont, ReadBookConfig.titleFont, (0..2).mapNotNull { ReadBookConfig.durConfig.getBgPath(it) })
    override fun dispatch(update: BgTextUpdate) {
        if (update.reviewCache) { ChapterProvider.clearReviewIconCache(); ChapterProvider.refreshReviewColumnsForStyleChange() }
        if (update.codes.isNotEmpty()) postEvent(EventBus.UP_CONFIG, ArrayList(update.codes))
        if (update.actionBar && AppConfig.readBarStyleFollowPage) postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
    }
    override fun save() { ReadBookConfig.save() }
}
