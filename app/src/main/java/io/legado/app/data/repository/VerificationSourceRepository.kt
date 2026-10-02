package io.legado.app.data.repository

import io.legado.app.help.source.SourceHelp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface VerificationSourceRepository {
    suspend fun disable(origin: String, type: Int)
    suspend fun delete(origin: String, type: Int)
}

internal object DefaultVerificationSourceRepository : VerificationSourceRepository {
    override suspend fun disable(origin: String, type: Int) = withContext(Dispatchers.IO) {
        SourceHelp.enableSource(origin, type, false)
    }
    override suspend fun delete(origin: String, type: Int) = withContext(Dispatchers.IO) {
        SourceHelp.deleteSource(origin, type)
    }
}
