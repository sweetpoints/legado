package io.legado.app.data.preferences

import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface ContentSearchAppearanceRepository { suspend fun eInk(): Boolean }
internal class AppContentSearchAppearanceRepository : ContentSearchAppearanceRepository {
    override suspend fun eInk() = withContext(Dispatchers.IO) { AppConfig.isEInkMode }
}
