package io.legado.app.data.preferences

import io.legado.app.data.repository.ChapterSourceSearchRequest
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ChapterSourceOption { Author, Info, Toc, WordCount, ResponseTime }
internal data class ChapterSourceSettings(val group: String, val author: Boolean, val info: Boolean, val toc: Boolean,
    val wordCount: Boolean, val responseTime: Boolean, val filterMode: Int, val minimum: Int, val maximum: Int) {
    fun apply(request: ChapterSourceSearchRequest) = request.copy(group = group, checkAuthor = author, loadInfo = info,
        loadToc = toc, loadWordCount = wordCount, sortResponseTime = responseTime, filterMode = filterMode, minimum = minimum, maximum = maximum)
}
internal interface ChapterSourceSettingsRepository {
    suspend fun load(): ChapterSourceSettings
    suspend fun toggle(option: ChapterSourceOption): ChapterSourceSettings
    suspend fun group(value: String): ChapterSourceSettings
}
internal class AppChapterSourceSettingsRepository(private val io: CoroutineDispatcher = Dispatchers.IO) : ChapterSourceSettingsRepository {
    private fun current() = ChapterSourceSettings(AppConfig.searchGroup, AppConfig.changeSourceCheckAuthor,
        AppConfig.changeSourceLoadInfo, AppConfig.changeSourceLoadToc, AppConfig.changeSourceLoadWordCount,
        AppConfig.changeSourceSortRespondTime, AppConfig.changeSourceWordCountFilterMode,
        AppConfig.changeSourceWordCountFilterMin, AppConfig.changeSourceWordCountFilterMax)
    override suspend fun load() = withContext(io) { current() }
    override suspend fun toggle(option: ChapterSourceOption) = withContext(io) {
        when (option) {
            ChapterSourceOption.Author -> AppConfig.changeSourceCheckAuthor = !AppConfig.changeSourceCheckAuthor
            ChapterSourceOption.Info -> AppConfig.changeSourceLoadInfo = !AppConfig.changeSourceLoadInfo
            ChapterSourceOption.Toc -> AppConfig.changeSourceLoadToc = !AppConfig.changeSourceLoadToc
            ChapterSourceOption.WordCount -> AppConfig.changeSourceLoadWordCount = !AppConfig.changeSourceLoadWordCount
            ChapterSourceOption.ResponseTime -> AppConfig.changeSourceSortRespondTime = !AppConfig.changeSourceSortRespondTime
        }
        current()
    }
    override suspend fun group(value: String) = withContext(io) { AppConfig.searchGroup = value; current() }
}
