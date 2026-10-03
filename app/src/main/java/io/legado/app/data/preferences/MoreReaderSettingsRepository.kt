package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface MoreReaderSetting {
    val key: String
    val title: Int
    val summary: Int?

    data class Toggle(
        override val key: String,
        override val title: Int,
        val defaultValue: Boolean = false,
        override val summary: Int? = null,
    ) : MoreReaderSetting

    data class Choice(
        override val key: String,
        override val title: Int,
        val labels: Int,
        val values: Int,
        val defaultValue: String,
        override val summary: Int? = null,
    ) : MoreReaderSetting

    data class SeekBar(
        override val key: String,
        override val title: Int,
        val defaultValue: Int,
        val minimum: Int,
        val maximum: Int,
        val increment: Int,
        override val summary: Int? = null,
    ) : MoreReaderSetting

    data class Action(
        override val key: String,
        override val title: Int,
        override val summary: Int? = null,
        val numericDefault: Int? = null,
        val numericMaximum: Int? = null,
    ) : MoreReaderSetting
}

/** The complete former pref_config_read.xml catalog in its original display order. */
object MoreReaderSettings {
    val all: List<MoreReaderSetting> =
        listOf(
            MoreReaderSetting.Choice(
                PreferKey.screenOrientation,
                R.string.screen_direction,
                R.array.screen_direction_title,
                R.array.screen_direction_value,
                "0",
            ),
            MoreReaderSetting.Choice(
                PreferKey.keepLight,
                R.string.keep_light,
                R.array.screen_time_out,
                R.array.screen_time_out_value,
                "0",
            ),
            MoreReaderSetting.Toggle(PreferKey.hideStatusBar, R.string.pt_hide_status_bar),
            MoreReaderSetting.Toggle(PreferKey.hideNavigationBar, R.string.pt_hide_navigation_bar),
            MoreReaderSetting.Toggle(PreferKey.readBodyToLh, R.string.read_body_to_lh, true),
            MoreReaderSetting.Toggle(
                PreferKey.paddingDisplayCutouts,
                R.string.padding_display_cutouts,
            ),
            MoreReaderSetting.Choice(
                PreferKey.doublePageHorizontal,
                R.string.double_page_horizontal,
                R.array.double_page_title,
                R.array.double_page_value,
                "0",
            ),
            MoreReaderSetting.Choice(
                PreferKey.progressBarBehavior,
                R.string.progress_bar_behavior,
                R.array.progress_bar_behavior_title,
                R.array.progress_bar_behavior_value,
                "page",
            ),
            MoreReaderSetting.Toggle(PreferKey.useZhLayout, R.string.use_zh_layout),
            MoreReaderSetting.Toggle(PreferKey.textFullJustify, R.string.text_full_justify, true),
            MoreReaderSetting.Toggle(
                PreferKey.hangingPunctuation,
                R.string.hanging_punctuation,
                summary = R.string.hanging_punctuation_summary,
            ),
            MoreReaderSetting.Choice(
                PreferKey.punctuationCompress,
                R.string.punctuation_compress,
                R.array.punctuation_compress_title,
                R.array.punctuation_compress_value,
                "none",
                R.string.punctuation_compress_summary,
            ),
            MoreReaderSetting.Toggle(
                PreferKey.textBottomJustify,
                R.string.text_bottom_justify,
                true,
            ),
            MoreReaderSetting.Toggle(
                PreferKey.adaptSpecialStyle,
                R.string.adapt_special_style,
                true,
            ),
            MoreReaderSetting.Toggle(PreferKey.mouseWheelPage, R.string.mouse_wheel_page, true),
            MoreReaderSetting.SeekBar(
                PreferKey.mouseWheelScrollSpeed,
                R.string.mouse_wheel_scroll_speed,
                100,
                10,
                400,
                10,
                R.string.mouse_wheel_scroll_speed_summary,
            ),
            MoreReaderSetting.Toggle(PreferKey.volumeKeyPage, R.string.volume_key_page, true),
            MoreReaderSetting.Toggle(
                PreferKey.volumeKeyPageOnPlay,
                R.string.volume_key_page_on_play,
            ),
            MoreReaderSetting.Toggle(PreferKey.keyPageOnLongPress, R.string.key_page_on_long_press),
            MoreReaderSetting.Toggle(
                PreferKey.pullToToggleBookmark,
                R.string.pull_to_toggle_bookmark,
                summary = R.string.pull_to_toggle_bookmark_summary,
            ),
            MoreReaderSetting.Action(
                PreferKey.pullBookmarkDistance,
                R.string.pull_bookmark_distance_title,
                R.string.pull_bookmark_distance_summary,
                numericDefault = 0,
                numericMaximum = 9999,
            ),
            MoreReaderSetting.Action(
                PreferKey.pageTouchSlop,
                R.string.page_touch_slop_title,
                R.string.page_touch_slop_summary,
                numericDefault = 0,
                numericMaximum = 9999,
            ),
            MoreReaderSetting.Action(
                PreferKey.pageTouchClick,
                R.string.page_touch_click_title,
                R.string.page_touch_click_summary,
                numericDefault = 0,
                numericMaximum = 399,
            ),
            MoreReaderSetting.Toggle(PreferKey.autoChangeSource, R.string.auto_change_source, true),
            MoreReaderSetting.Toggle(PreferKey.textSelectAble, R.string.selectText, true),
            MoreReaderSetting.Toggle(
                PreferKey.longPressSelectParagraph,
                R.string.long_press_select_paragraph,
                summary = R.string.long_press_select_paragraph_summary,
            ),
            MoreReaderSetting.Toggle(
                PreferKey.twoFingerReplacePreview,
                R.string.two_finger_replace_preview,
                summary = R.string.two_finger_replace_preview_summary,
            ),
            MoreReaderSetting.Toggle(
                PreferKey.showBrightnessView,
                R.string.show_brightness_view,
                true,
            ),
            MoreReaderSetting.Toggle(PreferKey.noAnimScrollPage, R.string.no_anim_scroll_page),
            MoreReaderSetting.Choice(
                PreferKey.clickImgWay,
                R.string.click_image_way,
                R.array.click_image_way_title,
                R.array.click_image_way_value,
                "0",
            ),
            MoreReaderSetting.Choice(
                PreferKey.highlightActionTrigger,
                R.string.highlight_action_trigger,
                R.array.highlight_action_trigger_title,
                R.array.highlight_action_trigger_value,
                "click",
            ),
            MoreReaderSetting.Toggle(PreferKey.optimizeRender, R.string.enable_optimize_render),
            MoreReaderSetting.Action("clickRegionalConfig", R.string.click_regional_config),
            MoreReaderSetting.Toggle("disableReturnKey", R.string.disable_return_key),
            MoreReaderSetting.Action("customPageKey", R.string.custom_page_key),
            MoreReaderSetting.Action(
                "customTextMenu",
                R.string.text_select_menu_config,
                R.string.text_select_menu_config_summary,
            ),
            MoreReaderSetting.Action(
                "customReaderMenu",
                R.string.reader_menu_config,
                R.string.reader_menu_config_summary,
            ),
            MoreReaderSetting.Toggle(
                PreferKey.showReadTitleAddition,
                R.string.show_read_title_addition,
                true,
            ),
            MoreReaderSetting.Toggle(
                PreferKey.showReadTitleChapterNameOnly,
                R.string.show_read_title_chapter_name_only,
                false,
            ),
            MoreReaderSetting.Toggle(
                PreferKey.readBarStyleFollowPage,
                R.string.read_bar_style_follow_page,
            ),
            MoreReaderSetting.Toggle(
                PreferKey.showBookMemo,
                R.string.show_book_memo,
                summary = R.string.show_book_memo_summary,
            ),
        )
}

/** SharedPreferences access and change observation for More reader settings. */
class MoreReaderSettingsRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
    private val listeners = linkedSetOf<(String) -> Unit>()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) synchronized(listeners) { listeners.toList() }.forEach { it(key) }
    }

    fun observe(listener: (String) -> Unit): AutoCloseable {
        synchronized(listeners) {
            if (listeners.isEmpty())
                preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            listeners += listener
        }
        return AutoCloseable {
            synchronized(listeners) {
                listeners -= listener
                if (listeners.isEmpty())
                    preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            }
        }
    }

    suspend fun load(): Map<String, String> =
        withContext(Dispatchers.IO) {
            MoreReaderSettings.all.associate { setting ->
                setting.key to readValue(setting)
            }
        }

    suspend fun save(setting: MoreReaderSetting, value: String) {
        withContext(Dispatchers.IO) {
            when (setting) {
                is MoreReaderSetting.Toggle ->
                    applicationContext.putPrefBoolean(setting.key, value.toBoolean())
                is MoreReaderSetting.Choice -> applicationContext.putPrefString(setting.key, value)
                is MoreReaderSetting.SeekBar ->
                    preferences
                        .edit()
                        .putInt(
                            setting.key,
                            value.toInt().coerceIn(setting.minimum, setting.maximum),
                        )
                        .apply()
                is MoreReaderSetting.Action -> Unit
            }
        }
    }

    suspend fun saveNumber(setting: MoreReaderSetting.Action, value: Int) {
        withContext(Dispatchers.IO) {
            val maximum =
                requireNotNull(setting.numericMaximum) {
                    "${setting.key} does not store a numeric preference"
                }
            requireNotNull(setting.numericDefault) {
                "${setting.key} does not store a numeric preference"
            }
            preferences.edit().putInt(setting.key, value.coerceIn(0, maximum)).apply()
        }
    }

    suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { preferences.edit().remove(key).apply() }
    }

    private fun readValue(setting: MoreReaderSetting): String =
        when (setting) {
            is MoreReaderSetting.Toggle ->
                applicationContext.getPrefBoolean(setting.key, setting.defaultValue).toString()
            is MoreReaderSetting.Choice ->
                applicationContext.getPrefString(setting.key, setting.defaultValue)
                    ?: setting.defaultValue
            is MoreReaderSetting.SeekBar ->
                preferences.getInt(setting.key, setting.defaultValue).toString()
            is MoreReaderSetting.Action ->
                setting.numericDefault
                    ?.let { preferences.getInt(setting.key, it).toString() }
                    .orEmpty()
        }
}
