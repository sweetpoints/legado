package io.legado.app.data.preferences

import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface MangaEpaperPreferences {
    suspend fun loadThreshold(): Int

    fun saveThreshold(threshold: Int)
}

class AppMangaEpaperPreferences : MangaEpaperPreferences {
    override suspend fun loadThreshold(): Int =
        withContext(Dispatchers.IO) {
            AppConfig.mangaEInkThreshold
        }

    // SharedPreferences.apply updates the in-memory value immediately and queues disk I/O.
    override fun saveThreshold(threshold: Int) {
        AppConfig.mangaEInkThreshold = threshold
    }
}
