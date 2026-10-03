package io.legado.app.data.preferences

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt

enum class SleepTimerMode {
    Minutes,
    Chapters,
}

interface SleepTimerPreferences {
    fun lastCustom(mode: SleepTimerMode): Int

    fun rememberCustom(mode: SleepTimerMode, value: Int)

    fun preferChapters(chapters: Boolean)
}

class AppSleepTimerPreferences(context: Context) : SleepTimerPreferences {
    private val context = context.applicationContext

    override fun lastCustom(mode: SleepTimerMode): Int = context.getPrefInt(key(mode), 0)

    override fun rememberCustom(mode: SleepTimerMode, value: Int) {
        context.putPrefInt(key(mode), value)
    }

    override fun preferChapters(chapters: Boolean) {
        AppConfig.sleepTimerPreferChapter = chapters
    }

    private fun key(mode: SleepTimerMode) =
        if (mode == SleepTimerMode.Minutes) PreferKey.lastSleepTimer else PreferKey.lastSleepChapter
}
