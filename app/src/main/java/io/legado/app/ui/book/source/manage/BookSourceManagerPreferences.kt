package io.legado.app.ui.book.source.manage

import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SourceManagerPreferenceSnapshot(
    val showStatus: Boolean = false,
    val blockNavigation: Boolean = false,
)

/** Preferences are repository IO; constructing a page never reads disk on Main. */
internal interface SourceManagerPreferences {
    suspend fun read(): SourceManagerPreferenceSnapshot

    suspend fun write(value: SourceManagerPreferenceSnapshot)
}

internal class AppSourceManagerPreferences : SourceManagerPreferences {
    override suspend fun read(): SourceManagerPreferenceSnapshot =
        withContext(Dispatchers.IO) {
            SourceManagerPreferenceSnapshot(
                showStatus = AppConfig.showSourceCheckStatus,
                blockNavigation = AppConfig.blockSourceNavigation,
            )
        }

    override suspend fun write(value: SourceManagerPreferenceSnapshot) =
        withContext(Dispatchers.IO) {
            AppConfig.showSourceCheckStatus = value.showStatus
            AppConfig.blockSourceNavigation = value.blockNavigation
        }
}
