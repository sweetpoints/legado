package io.legado.app.data.repository

import android.net.Uri
import io.legado.app.constant.AppConst
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Memory-only seeds from the existing importer; full Book metadata remains with its pipeline. */
data class SharedLocalBookPreviewSeed(
    val uri: String,
    val title: String?,
    val onBookshelf: Boolean = false,
    val directory: Boolean = false,
)

data class SharedLocalBookPreviewRow(
    val id: String,
    val title: String,
    val name: String,
    val directory: Boolean,
    val onBookshelf: Boolean,
    val format: String,
    val size: String,
    val date: String,
) {
    val selectable
        get() = !directory && !onBookshelf
}

interface SharedLocalBookPreviewRepository {
    suspend fun project(seeds: List<SharedLocalBookPreviewSeed>): List<SharedLocalBookPreviewRow>
}

class FileSharedLocalBookPreviewRepository : SharedLocalBookPreviewRepository {
    override suspend fun project(seeds: List<SharedLocalBookPreviewSeed>) =
        withContext(Dispatchers.IO) {
            seeds
                .distinctBy { it.uri }
                .map { seed ->
                    val document = FileDoc.fromUri(Uri.parse(seed.uri), seed.directory)
                    SharedLocalBookPreviewRow(
                        id(seed.uri),
                        seed.title ?: document.name,
                        document.name,
                        document.isDir,
                        seed.onBookshelf,
                        document.name.substringAfterLast('.'),
                        ConvertUtils.formatFileSize(document.size),
                        AppConst.dateFormat.format(document.lastModified),
                    )
                }
        }

    companion object {
        fun id(uri: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(uri.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun batch(seeds: List<SharedLocalBookPreviewSeed>): String =
            id(seeds.joinToString("") { "${it.uri.length}:${it.uri}" })
    }
}
