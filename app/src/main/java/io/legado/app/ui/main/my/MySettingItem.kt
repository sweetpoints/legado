package io.legado.app.ui.main.my

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.legado.app.R

enum class MySettingKind {
    Action,
    Switch,
    ThemeChoice,
}

data class MySettingItem(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int?,
    @DrawableRes val iconRes: Int,
    @StringRes val categoryRes: Int?,
    val kind: MySettingKind,
)

val defaultMyMoreItems = setOf("check_update", "check_beta_update")

val mySettingItems =
    listOf(
        MySettingItem(
            "bookSourceManage",
            R.string.book_source_manage,
            R.string.book_source_manage_desc,
            R.drawable.ic_cfg_source,
            null,
            MySettingKind.Action,
        ),
        MySettingItem(
            "autoTaskManage",
            R.string.auto_task_manage,
            R.string.auto_task_manage_desc,
            R.drawable.ic_auto_page,
            null,
            MySettingKind.Action,
        ),
        MySettingItem(
            "autoTaskService",
            R.string.auto_task_service,
            R.string.auto_task_service_desc,
            R.drawable.ic_auto_page,
            null,
            MySettingKind.Switch,
        ),
        MySettingItem(
            "txtTocRuleManage",
            R.string.txt_toc_rule,
            R.string.config_txt_toc_rule,
            R.drawable.ic_cfg_source,
            null,
            MySettingKind.Action,
        ),
        MySettingItem(
            "replaceManage",
            R.string.replace_purify,
            R.string.replace_purify_desc,
            R.drawable.ic_cfg_replace,
            null,
            MySettingKind.Action,
        ),
        MySettingItem(
            "dictRuleManage",
            R.string.dict_rule,
            R.string.config_dict_rule,
            R.drawable.ic_translate,
            null,
            MySettingKind.Action,
        ),
        MySettingItem(
            "themeMode",
            R.string.theme_mode,
            R.string.theme_mode_desc,
            R.drawable.ic_cfg_theme,
            null,
            MySettingKind.ThemeChoice,
        ),
        MySettingItem(
            "webService",
            R.string.web_service,
            R.string.web_service_desc,
            R.drawable.ic_cfg_web,
            null,
            MySettingKind.Switch,
        ),
        MySettingItem(
            "mcpService",
            R.string.mcp_service,
            R.string.mcp_service_desc,
            R.drawable.ic_cfg_web,
            null,
            MySettingKind.Switch,
        ),
        MySettingItem(
            "web_dav_setting",
            R.string.backup_restore,
            R.string.web_dav_set_import_old,
            R.drawable.ic_cfg_backup,
            R.string.setting,
            MySettingKind.Action,
        ),
        MySettingItem(
            "theme_setting",
            R.string.theme_setting,
            R.string.theme_setting_s,
            R.drawable.ic_cfg_theme,
            R.string.setting,
            MySettingKind.Action,
        ),
        MySettingItem(
            "setting",
            R.string.other_setting,
            R.string.other_setting_s,
            R.drawable.ic_cfg_other,
            R.string.setting,
            MySettingKind.Action,
        ),
        MySettingItem(
            "bookmark",
            R.string.bookmark,
            R.string.all_bookmark,
            R.drawable.ic_bookmark,
            R.string.other,
            MySettingKind.Action,
        ),
        MySettingItem(
            "readRecord",
            R.string.read_record,
            R.string.read_record_summary,
            R.drawable.ic_history,
            R.string.other,
            MySettingKind.Action,
        ),
        MySettingItem(
            "fileManage",
            R.string.file_manage,
            R.string.file_manage_summary,
            R.drawable.ic_folder_outline,
            R.string.other,
            MySettingKind.Action,
        ),
        MySettingItem(
            "about",
            R.string.about,
            null,
            R.drawable.ic_cfg_about,
            R.string.other,
            MySettingKind.Action,
        ),
        MySettingItem(
            "check_update",
            R.string.check_update,
            null,
            R.drawable.ic_download,
            R.string.other,
            MySettingKind.Action,
        ),
        MySettingItem(
            "check_beta_update",
            R.string.check_beta_update,
            null,
            R.drawable.ic_download,
            R.string.other,
            MySettingKind.Action,
        ),
        MySettingItem(
            "exit",
            R.string.exit,
            null,
            R.drawable.ic_exit,
            R.string.other,
            MySettingKind.Action,
        ),
        MySettingItem(
            "myMore",
            R.string.reader_menu_more,
            null,
            R.drawable.ic_more_vert,
            null,
            MySettingKind.Action,
        ),
    )

fun visibleMySettings(moreItems: Set<String>, isMore: Boolean): List<MySettingItem> =
    mySettingItems.filter {
        when (it.key) {
            "myMore",
            "exit" -> !isMore
            else -> (it.key in moreItems) == isMore
        }
    }

val customizableMySettings = mySettingItems.filter { it.key != "myMore" && it.key != "exit" }
