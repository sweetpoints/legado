package io.legado.app.data.preferences

import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Immutable snapshot; the reader's mutable configuration never becomes UI state. */
data class MangaColorFilterValues(
    val brightness: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val blue: Int = 0,
    val alpha: Int = 0,
) {
    fun bounded() = MangaColorFilterValues(brightness.coerceIn(0, 255), red.coerceIn(0, 255),
        green.coerceIn(0, 255), blue.coerceIn(0, 255), alpha.coerceIn(0, 255))

    fun toReaderConfig() = MangaColorFilterConfig(r = red, g = green, b = blue, a = alpha, l = brightness)
}

interface MangaColorFilterRepository {
    suspend fun load(): MangaColorFilterValues
    /** SharedPreferences.apply enqueues disk persistence; safe to finish before the ViewModel clears. */
    fun save(values: MangaColorFilterValues)
}

class PreferenceMangaColorFilterRepository : MangaColorFilterRepository {
    override suspend fun load(): MangaColorFilterValues = withContext(Dispatchers.IO) {
        val value = GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter).getOrNull()
            ?: MangaColorFilterConfig()
        MangaColorFilterValues(value.l, value.r, value.g, value.b, value.a).bounded()
    }

    override fun save(values: MangaColorFilterValues) {
        AppConfig.mangaColorFilter = values.toReaderConfig().toJson()
    }
}
