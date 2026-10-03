package io.legado.app.data.repository

import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.utils.isAbsUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RssSourceManagementShareFeedback(val url: String, val summary: String = "", val canEncode: Boolean = false,
    val passphrase: String? = null)
interface RssSourceManagementSharingRepository {
    suspend fun feedback(url: String): RssSourceManagementShareFeedback
    suspend fun passphrase(url: String): String
}
/** Keep preference-backed upload metadata and the established RSS passphrase format outside UI. */
class AppRssSourceManagementSharingRepository(
    private val summary: () -> String = DirectLinkUpload::getSummary,
    private val expiryDays: () -> Int = DirectLinkUpload::getExpiryDate
) : RssSourceManagementSharingRepository {
    override suspend fun feedback(url: String) = withContext(Dispatchers.IO) {
        if (url.isAbsUrl()) RssSourceManagementShareFeedback(url, summary(), SourceSharePassphrase.canEncode(url))
        else RssSourceManagementShareFeedback(url)
    }
    override suspend fun passphrase(url: String): String = withContext(Dispatchers.IO) {
        SourceSharePassphrase.encode(url, SourceSharePassphrase.Type.RSS_SOURCE, expiryDays())
    }
}
