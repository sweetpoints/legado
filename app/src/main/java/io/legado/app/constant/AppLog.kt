package io.legado.app.constant

import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import splitties.init.appCtx

data class AppLogEntry(
    val id: Long,
    val time: Long,
    val message: String,
    val throwable: Throwable?,
)

object AppLog {

    private val mLogs = arrayListOf<AppLogEntry>()
    private var nextEntryId = 0L
    private val mutableEntries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()

    val logs
        @Synchronized get() = mLogs.map { Triple(it.time, it.message, it.throwable) }

    @Synchronized
    fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size >= 100) {
            mLogs.removeLastOrNull()
        }
        if (throwable == null) {
            LogUtils.d("AppLog", message)
        } else {
            LogUtils.d("AppLog", "$message\n${throwable.stackTraceToString()}")
        }
        mLogs.add(0, AppLogEntry(++nextEntryId, System.currentTimeMillis(), message, throwable))
        mutableEntries.value = mLogs.toList()
        runCatching { postEvent(EventBus.APP_LOG_UPDATED, true) }
        if (BuildConfig.DEBUG) {
            runCatching {
                val stackTrace = Thread.currentThread().stackTrace
                Log.e(stackTrace[3].className, message, throwable)
            }
        }
    }

    @Synchronized
    fun putNotSave(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size >= 100) {
            mLogs.removeLastOrNull()
        }
        mLogs.add(0, AppLogEntry(++nextEntryId, System.currentTimeMillis(), message, throwable))
        mutableEntries.value = mLogs.toList()
        runCatching { postEvent(EventBus.APP_LOG_UPDATED, true) }
        if (BuildConfig.DEBUG) {
            runCatching {
                val stackTrace = Thread.currentThread().stackTrace
                Log.e(stackTrace[3].className, message, throwable)
            }
        }
    }

    @Synchronized
    fun clear() {
        mLogs.clear()
        mutableEntries.value = emptyList()
        runCatching { postEvent(EventBus.APP_LOG_UPDATED, true) }
    }

    fun exportText(entries: List<Triple<Long, String, Throwable?>>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return buildString {
            entries.asReversed().forEach { (time, message, throwable) ->
                append(dateFormat.format(Date(time)))
                append(' ')
                appendLine(message)
                throwable?.let {
                    appendLine(it.stackTraceToString().trimEnd().prependIndent("    "))
                }
            }
        }
    }

    fun putDebug(message: String?, throwable: Throwable? = null) {
        if (AppConfig.recordLog) {
            put(message, throwable)
        }
    }
}
