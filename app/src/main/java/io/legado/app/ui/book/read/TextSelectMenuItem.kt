package io.legado.app.ui.book.read

import android.content.Context
import io.legado.app.R
import io.legado.app.help.TextSelectMenuConfig

enum class TextSelectMenuItem(val key: String, val menuId: Int?, val titleRes: Int) {
    Replace(TextSelectMenuConfig.KEY_REPLACE, R.id.menu_replace, R.string.replace),
    Copy(TextSelectMenuConfig.KEY_COPY, R.id.menu_copy, android.R.string.copy),
    Bookmark(TextSelectMenuConfig.KEY_BOOKMARK, R.id.menu_bookmark, R.string.bookmark),
    Highlight(TextSelectMenuConfig.KEY_HIGHLIGHT, R.id.menu_highlight, R.string.highlight),
    Aloud(TextSelectMenuConfig.KEY_ALOUD, R.id.menu_aloud, R.string.read_aloud),
    Dict(TextSelectMenuConfig.KEY_DICT, R.id.menu_dict, R.string.dict),
    Search(TextSelectMenuConfig.KEY_SEARCH, R.id.menu_search_content, R.string.search_content),
    Browser(TextSelectMenuConfig.KEY_BROWSER, R.id.menu_browser, R.string.browser),
    Share(TextSelectMenuConfig.KEY_SHARE, R.id.menu_share_str, R.string.share),
    ProcessText(TextSelectMenuConfig.KEY_PROCESS_TEXT, null, R.string.process_text_actions);

    companion object {
        val byKey: Map<String, TextSelectMenuItem> = entries.associateBy { it.key }
    }
}

// Compatibility entry points for the remaining View reader; persistence belongs to data.
fun loadTextSelectMenuConfig(context: Context): TextSelectMenuConfig =
    io.legado.app.data.preferences.loadTextSelectMenuConfig(context)

fun saveTextSelectMenuConfig(context: Context, config: TextSelectMenuConfig) =
    io.legado.app.data.preferences.saveTextSelectMenuConfig(context, config)
