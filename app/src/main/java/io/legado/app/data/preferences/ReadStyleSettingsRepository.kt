package io.legado.app.data.preferences

import com.github.liuyueyi.quick.transfer.constants.TransType
import io.legado.app.constant.EventBus
import io.legado.app.help.book.ResourceThemeGeneration
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent

/** Progress values match the old DetailSeekBar, including one-step adjustments. */
enum class ReadStyleSlider(val maximum: Int) {
    TextSize(45),
    LetterSpacing(100),
    LineSpacing(50),
    ParagraphSpacing(20);

    fun display(progress: Int): String =
        when (this) {
            TextSize -> (progress + 5).toString()
            LetterSpacing -> ((progress - 50) / 100f).toString()
            LineSpacing -> ((progress - 20) / 10f).toString()
            ParagraphSpacing -> (progress / 10f).toString()
        }
}

data class ReadStylePreset(
    val index: Int,
    val name: String,
    val textColor: Int,
    val configuration: String,
)

data class ReadStyleSettingsSnapshot(
    val presets: List<ReadStylePreset>,
    val selected: Int,
    val shared: Boolean,
    val textSize: Int,
    val letterSpacing: Int,
    val lineSpacing: Int,
    val paragraphSpacing: Int,
    val font: String,
    val weight: Int,
    val chinese: Int,
    val indent: Int,
    val pageAnimation: Int,
) {
    fun progress(slider: ReadStyleSlider): Int =
        when (slider) {
            ReadStyleSlider.TextSize -> textSize
            ReadStyleSlider.LetterSpacing -> letterSpacing
            ReadStyleSlider.LineSpacing -> lineSpacing
            ReadStyleSlider.ParagraphSpacing -> paragraphSpacing
        }.coerceIn(0, slider.maximum)
}

data class ReadStyleUpdate(
    val animationChanged: Boolean = false,
    val reloadContent: Boolean = false,
    val codes: List<Int> = emptyList(),
    val updateActionBar: Boolean = false,
)

interface ReadStyleSettingsRepository {
    fun load(): ReadStyleSettingsSnapshot

    fun checkpoint(): String

    fun restore(checkpoint: String)

    fun slider(slider: ReadStyleSlider, progress: Int): ReadStyleUpdate

    fun select(index: Int): ReadStyleUpdate

    fun shared(value: Boolean): ReadStyleUpdate

    fun animation(value: Int): ReadStyleUpdate

    fun weight(value: Int): ReadStyleUpdate

    fun chinese(value: Int): ReadStyleUpdate

    fun indent(value: Int): ReadStyleUpdate

    fun font(path: String): ReadStyleUpdate

    fun addPreset(): Int

    fun dispatch(update: ReadStyleUpdate)

    fun save()
}

private data class ReadStyleCheckpoint(
    val presets: List<ReadBookConfig.Config>,
    val sharedConfig: ReadBookConfig.Config,
    val selected: Int,
    val shared: Boolean,
    val chinese: Int,
    val book: String?,
    val bookAnimation: Int?,
    val readSelection: Int? = null,
    val comicSelection: Int? = null,
)

class AppReadStyleSettingsRepository : ReadStyleSettingsRepository {
    override fun load(): ReadStyleSettingsSnapshot =
        ReadStyleSettingsSnapshot(
            ReadBookConfig.configList.mapIndexed { index, config ->
                ReadStylePreset(
                    index,
                    config.name.ifBlank { "文字" },
                    config.curTextColor(),
                    GSON.toJson(config),
                )
            },
            ReadBookConfig.styleSelect,
            ReadBookConfig.shareLayout,
            ReadBookConfig.textSize - 5,
            (ReadBookConfig.letterSpacing * 100).toInt() + 50,
            ReadBookConfig.lineSpacingExtra + 10,
            ReadBookConfig.paragraphSpacing,
            ReadBookConfig.textFont,
            ReadBookConfig.textBold,
            AppConfig.chineseConverterType,
            ReadBookConfig.paragraphIndent.length,
            ReadBook.pageAnim(),
        )

    override fun checkpoint(): String =
        GSON.toJson(
            ReadStyleCheckpoint(
                ReadBookConfig.configList.toList(),
                ReadBookConfig.shareConfig,
                ReadBookConfig.styleSelect,
                ReadBookConfig.shareLayout,
                AppConfig.chineseConverterType,
                ReadBook.book?.bookUrl,
                ReadBook.book?.config?.pageAnim,
                ReadBookConfig.readStyleSelect,
                ReadBookConfig.comicStyleSelect,
            )
        )

    override fun restore(checkpoint: String) {
        val value = GSON.fromJsonObject<ReadStyleCheckpoint>(checkpoint).getOrNull() ?: return
        if (value.presets.size < 5 || value.selected !in value.presets.indices) return
        ReadBookConfig.configList.clear()
        ReadBookConfig.configList.addAll(value.presets)
        ReadBookConfig.shareConfig = value.sharedConfig
        if (value.readSelection != null && value.comicSelection != null) {
            ReadBookConfig.readStyleSelect = value.readSelection.coerceIn(value.presets.indices)
            ReadBookConfig.comicStyleSelect = value.comicSelection.coerceIn(value.presets.indices)
        } else ReadBookConfig.styleSelect = value.selected
        ResourceThemeGeneration.changed()
        ReadBookConfig.shareLayout = value.shared
        AppConfig.chineseConverterType = value.chinese
        if (ReadBook.book?.bookUrl == value.book) ReadBook.book?.setPageAnim(value.bookAnimation)
    }

    override fun slider(slider: ReadStyleSlider, progress: Int): ReadStyleUpdate {
        val p = progress.coerceIn(0, slider.maximum)
        when (slider) {
            ReadStyleSlider.TextSize -> ReadBookConfig.textSize = p + 5
            ReadStyleSlider.LetterSpacing -> ReadBookConfig.letterSpacing = (p - 50) / 100f
            ReadStyleSlider.LineSpacing -> ReadBookConfig.lineSpacingExtra = p - 10
            ReadStyleSlider.ParagraphSpacing -> ReadBookConfig.paragraphSpacing = p
        }
        return ReadStyleUpdate(codes = listOf(8, 5))
    }

    override fun select(index: Int): ReadStyleUpdate {
        if (index == ReadBookConfig.styleSelect || index !in ReadBookConfig.configList.indices)
            return ReadStyleUpdate()
        val before = ReadBook.pageAnim()
        ReadBookConfig.styleSelect = index
        return ReadStyleUpdate(
            animationChanged = before != ReadBook.pageAnim(),
            codes = listOf(1, 2, 5),
            updateActionBar = AppConfig.readBarStyleFollowPage,
        )
    }

    override fun shared(value: Boolean): ReadStyleUpdate {
        if (ReadBookConfig.shareLayout == value) return ReadStyleUpdate()
        val before = ReadBook.pageAnim()
        ReadBookConfig.shareLayout = value
        return ReadStyleUpdate(
            animationChanged = before != ReadBook.pageAnim(),
            codes = listOf(1, 2, 5),
        )
    }

    override fun animation(value: Int): ReadStyleUpdate {
        ReadBook.book?.setPageAnim(-1)
        ReadBookConfig.pageAnim = value.coerceIn(0, 4)
        return ReadStyleUpdate(animationChanged = true, reloadContent = true)
    }

    override fun weight(value: Int): ReadStyleUpdate {
        ReadBookConfig.textBold = value.coerceIn(0, 2)
        return ReadStyleUpdate(codes = listOf(8, 9, 6))
    }

    override fun chinese(value: Int): ReadStyleUpdate {
        AppConfig.chineseConverterType = value.coerceIn(0, 2)
        ChineseUtils.unLoad(*TransType.entries.toTypedArray())
        return ReadStyleUpdate(codes = listOf(5))
    }

    override fun indent(value: Int): ReadStyleUpdate {
        ReadBookConfig.paragraphIndent = "　".repeat(value.coerceIn(0, 4))
        return ReadStyleUpdate(codes = listOf(8, 5))
    }

    override fun font(path: String): ReadStyleUpdate {
        if (path == ReadBookConfig.textFont && path.isNotEmpty()) return ReadStyleUpdate()
        ReadBookConfig.textFont = path
        return ReadStyleUpdate(codes = listOf(2, 5))
    }

    override fun addPreset(): Int {
        ReadBookConfig.configList.add(ReadBookConfig.Config())
        return ReadBookConfig.configList.lastIndex
    }

    override fun dispatch(update: ReadStyleUpdate) {
        if (update.reloadContent) ReadBook.loadContent(false)
        if (update.codes.isNotEmpty()) postEvent(EventBus.UP_CONFIG, ArrayList(update.codes))
        if (update.updateActionBar) postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
    }

    override fun save() {
        ReadBookConfig.save()
    }
}
