package io.legado.app.data.repository

import io.legado.app.help.source.SourceHelp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Source mutations are independent of Fragment callbacks and run off the UI thread. */
interface OpenUrlSourceRepository {
    suspend fun disableSource(origin: String, type: Int)

    suspend fun deleteSource(origin: String, type: Int)
}

class DefaultOpenUrlSourceRepository : OpenUrlSourceRepository {
    override suspend fun disableSource(origin: String, type: Int) =
        withContext(Dispatchers.IO) {
            SourceHelp.enableSource(origin, type, false)
        }

    override suspend fun deleteSource(origin: String, type: Int) =
        withContext(Dispatchers.IO) {
            SourceHelp.deleteSource(origin, type)
        }
}
