package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.os.Parcelable
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.BookshelfReadProgressMode
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getBooleanCompat
import io.legado.app.utils.postEvent
import kotlinx.parcelize.Parcelize

@Parcelize data class BookshelfSettingsDraft(val groupStyle: Int = 0, val progress: Int = 1,
    val unread: Boolean = true, val latest: Boolean = false, val waitCount: Boolean = false,
    val fastScroll: Boolean = false, val recent: Boolean = false, val stats: Boolean = false,
    val layout: Int = 0, val sort: Int = 0, val title: Int = 0, val margin: Int = 12) : Parcelable {
    fun normalized() = copy(groupStyle = groupStyle.takeIf { it in 0..1 } ?: 0,
        progress = progress.takeIf { it in 0..2 } ?: 1, layout = layout.takeIf { it in 0..6 } ?: 0,
        sort = sort.takeIf { it in 0..5 } ?: 0, title = title.takeIf { it in 0..2 } ?: 0,
        margin = margin.coerceIn(0, 60))
}
data class BookshelfSettingsEffects(val recreate: Boolean = false, val notifyMain: Boolean = false,
    val refreshCount: Int = 0, val updateWaitCount: Boolean = false, val updateSort: Boolean = false,
    val changedLayout: Int? = null)
internal fun bookshelfSettingsEffects(before: BookshelfSettingsDraft, after: BookshelfSettingsDraft) = BookshelfSettingsEffects(
    recreate = before.layout != after.layout || before.title != after.title || before.margin != after.margin ||
        before.recent != after.recent || before.stats != after.stats,
    notifyMain = before.groupStyle != after.groupStyle,
    refreshCount = listOf(before.unread != after.unread, before.latest != after.latest,
        before.progress != after.progress, before.fastScroll != after.fastScroll).count { it },
    updateWaitCount = before.waitCount != after.waitCount, updateSort = before.sort != after.sort,
    changedLayout = after.layout.takeIf { before.layout != after.layout })
interface BookshelfSettingsRepository {
    fun load(): BookshelfSettingsDraft
    fun commit(value: BookshelfSettingsDraft): BookshelfSettingsEffects
}
class PreferenceBookshelfSettingsRepository(context: Context,
    private val prefs: SharedPreferences = context.applicationContext.defaultSharedPreferences) : BookshelfSettingsRepository {
    override fun load(): BookshelfSettingsDraft {
        val raw = BookshelfSettingsDraft(prefs.getInt(PreferKey.bookGroupStyle, 0),
            BookshelfReadProgressMode.resolve(prefs.all[PreferKey.bookshelfReadProgressMode], prefs.all[PreferKey.showBookshelfReadProgress]),
            prefs.getBooleanCompat(PreferKey.showUnread, true), prefs.getBooleanCompat(PreferKey.showLastUpdateTime, false),
            prefs.getBooleanCompat(PreferKey.showWaitUpCount, false), prefs.getBooleanCompat(PreferKey.showBookshelfFastScroller, false),
            prefs.getBooleanCompat(PreferKey.showBookshelfRecentReading, false), prefs.getBooleanCompat(PreferKey.showBookshelfStats, false),
            prefs.getInt(PreferKey.bookshelfLayout, 0), prefs.getInt(PreferKey.bookshelfSort, 0),
            prefs.getInt(PreferKey.showBooknameLayout, 0), prefs.getInt(PreferKey.bookshelfMargin, 12))
        val normalized = raw.normalized()
        // Opening the old dialog repaired invalid numeric preferences without applying user drafts.
        if (raw != normalized) writeChanged(raw, normalized)
        return normalized
    }
    override fun commit(value: BookshelfSettingsDraft): BookshelfSettingsEffects {
        val before = load(); val after = value.normalized()
        writeChanged(before, after)
        return bookshelfSettingsEffects(before, after)
    }
    private fun writeChanged(before: BookshelfSettingsDraft, after: BookshelfSettingsDraft) {
        val editor = prefs.edit()
        fun number(key: String, old: Int, new: Int) { if (old != new) editor.putInt(key, new) }
        fun boolean(key: String, old: Boolean, new: Boolean) { if (old != new) editor.putBoolean(key, new) }
        number(PreferKey.bookGroupStyle, before.groupStyle, after.groupStyle)
        number(PreferKey.bookshelfReadProgressMode, before.progress, after.progress)
        if (before.progress != after.progress) editor.putBoolean(PreferKey.showBookshelfReadProgress, after.progress != BookshelfReadProgressMode.HIDDEN)
        number(PreferKey.bookshelfLayout, before.layout, after.layout); number(PreferKey.bookshelfSort, before.sort, after.sort)
        number(PreferKey.showBooknameLayout, before.title, after.title); number(PreferKey.bookshelfMargin, before.margin, after.margin)
        boolean(PreferKey.showUnread, before.unread, after.unread); boolean(PreferKey.showLastUpdateTime, before.latest, after.latest)
        boolean(PreferKey.showWaitUpCount, before.waitCount, after.waitCount)
        boolean(PreferKey.showBookshelfFastScroller, before.fastScroll, after.fastScroll)
        boolean(PreferKey.showBookshelfRecentReading, before.recent, after.recent); boolean(PreferKey.showBookshelfStats, before.stats, after.stats)
        editor.apply()
    }
}
/** Dispatch preserves the old event types and recreation precedence. */
internal fun BookshelfSettingsEffects.dispatchEvents(post: (String, Any) -> Unit = { tag, payload ->
    when (payload) { is String -> postEvent(tag, payload); is Boolean -> postEvent(tag, payload); else -> Unit }
}) {
    repeat(refreshCount) { post(EventBus.BOOKSHELF_REFRESH, "") }
    if (recreate) post(EventBus.RECREATE, "") else if (notifyMain) post(EventBus.NOTIFY_MAIN, false)
}
