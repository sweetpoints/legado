package io.legado.app.data.repository

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.model.CheckSource
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SourceCheckSettings(
    val timeout: Long,
    val comment: Boolean,
    val domain: Boolean,
    val search: Boolean,
    val discovery: Boolean,
    val info: Boolean,
    val category: Boolean,
    val content: Boolean,
)

interface SourceCheckSettingsRepository {
    suspend fun load(): SourceCheckSettings

    suspend fun save(settings: SourceCheckSettings)
}

class AppSourceCheckSettingsRepository(context: Context) : SourceCheckSettingsRepository {
    private val context = context.applicationContext

    override suspend fun load() =
        withContext(Dispatchers.IO) {
            lock.withLock {
                CheckSource.run {
                    SourceCheckSettings(
                        timeout,
                        wSourceComment,
                        checkDomain,
                        checkSearch,
                        checkDiscovery,
                        checkInfo,
                        checkCategory,
                        checkContent,
                    )
                }
            }
        }

    override suspend fun save(settings: SourceCheckSettings) =
        withContext(Dispatchers.IO + NonCancellable) {
            require(settings.timeout > 0)
            lock.withLock {
                CheckSource.apply {
                    timeout = settings.timeout
                    wSourceComment = settings.comment
                    checkDomain = settings.domain
                    checkSearch = settings.search
                    checkDiscovery = settings.discovery
                    checkInfo = settings.info
                    checkCategory = settings.category
                    checkContent = settings.content
                    putConfig()
                    context.putPrefString(PreferKey.checkSource, summary)
                }
            }
            Unit
        }

    private companion object {
        val lock = Mutex()
    }
}
