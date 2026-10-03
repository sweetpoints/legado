package io.legado.app.data.preferences

import android.content.Context
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ReadAloudControlPreferences(
    val followSystem: Boolean,
    val rate: Int,
    val defaultTimer: Int,
)

data class ReadAloudControlRuntime(val paused: Boolean, val minute: Int, val chapter: Int)

interface ReadAloudControlRepository {
    fun preferences(): ReadAloudControlPreferences

    fun runtime(): ReadAloudControlRuntime

    fun saveFollowSystem(follow: Boolean)

    fun saveRate(rate: Int)

    fun saveDefaultTimer(minute: Int)

    suspend fun engineName(): String
}

class AppReadAloudControlRepository(context: Context) : ReadAloudControlRepository {
    private val context = context.applicationContext

    override fun preferences() =
        ReadAloudControlPreferences(
            AppConfig.ttsFlowSys,
            AppConfig.ttsSpeechRate,
            AppConfig.ttsTimer,
        )

    override fun runtime() =
        ReadAloudControlRuntime(
            BaseReadAloudService.pause,
            BaseReadAloudService.timeMinute,
            BaseReadAloudService.chapterToStop,
        )

    override fun saveFollowSystem(follow: Boolean) {
        AppConfig.ttsFlowSys = follow
    }

    override fun saveRate(rate: Int) {
        AppConfig.ttsSpeechRate = rate
    }

    override fun saveDefaultTimer(minute: Int) {
        AppConfig.ttsTimer = minute
    }

    // HTTP TTS names are read from Room, so this must stay off the UI thread.
    override suspend fun engineName(): String =
        withContext(Dispatchers.IO) { ReadAloud.getEngineName(context) }
}
