package io.legado.app.data.repository

import androidx.annotation.Keep
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.utils.isAbsUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Keep
data class ReplaceManagementShareFeedback(
    val url: String,
    val summary: String = "",
    val canEncode: Boolean = false,
    val passphrase: String? = null,
)

interface ReplaceManagementSharingRepository {
    suspend fun feedback(url: String): ReplaceManagementShareFeedback

    suspend fun passphrase(url: String): String
}

/**
 * Keep preference-backed upload metadata and the established replacement-rule passphrase format
 * outside UI.
 */
class AppReplaceManagementSharingRepository(
    private val summary: () -> String = DirectLinkUpload::getSummary,
    private val expiryDays: () -> Int = DirectLinkUpload::getExpiryDate,
) : ReplaceManagementSharingRepository {
    override suspend fun feedback(url: String) =
        withContext(Dispatchers.IO) {
            if (url.isAbsUrl())
                ReplaceManagementShareFeedback(url, summary(), SourceSharePassphrase.canEncode(url))
            else ReplaceManagementShareFeedback(url)
        }

    override suspend fun passphrase(url: String): String =
        withContext(Dispatchers.IO) {
            SourceSharePassphrase.encode(url, SourceSharePassphrase.Type.REPLACE_RULE, expiryDays())
        }
}
