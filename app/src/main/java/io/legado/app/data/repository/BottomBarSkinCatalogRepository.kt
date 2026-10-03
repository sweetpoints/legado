package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.legado.app.help.BottomBarSkinFormat
import io.legado.app.help.BottomBarSkinManager
import io.legado.app.utils.FileDoc
import io.legado.app.utils.inputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BottomBarSkinCatalog(val names: List<String>, val active: String)

data class BottomBarSkinStaged(val session: String, val name: String, val editName: String? = null)

enum class BottomBarSkinCatalogIssue {
    Invalid,
    NoImages,
}

class BottomBarSkinCatalogException(val issue: BottomBarSkinCatalogIssue, cause: Throwable) :
    Exception(cause)

interface BottomBarSkinCatalogRepository {
    suspend fun load(): BottomBarSkinCatalog

    suspend fun preview(name: String, sizePx: Int): List<Bitmap>

    suspend fun activate(name: String): String

    suspend fun delete(name: String)

    suspend fun importZip(uri: String): BottomBarSkinStaged

    suspend fun edit(name: String): BottomBarSkinStaged

    suspend fun zip(name: String): String

    suspend fun discard(session: String)
}

/**
 * IO projection and staging ownership; the existing manager retains all skin-format transactions.
 */
class AppBottomBarSkinCatalogRepository(context: Context) : BottomBarSkinCatalogRepository {
    private val context = context.applicationContext
    private val previews = ConcurrentHashMap<String, List<Bitmap>>()

    override suspend fun load() =
        withContext(Dispatchers.IO) {
            previews.clear()
            BottomBarSkinCatalog(BottomBarSkinManager.list(), BottomBarSkinManager.active)
        }

    override suspend fun preview(name: String, sizePx: Int) =
        withContext(Dispatchers.IO) {
            previews.getOrPut("$sizePx:$name") {
                BottomBarSkinManager.getPreviewBitmaps(name, sizePx)
            }
        }

    override suspend fun activate(name: String) =
        withContext(Dispatchers.IO) {
            require(name.isEmpty() || BottomBarSkinManager.hasSkin(name))
            BottomBarSkinManager.active = name
            BottomBarSkinManager.active
        }

    override suspend fun delete(name: String) =
        withContext(Dispatchers.IO) {
            require(name.isNotEmpty())
            check(BottomBarSkinManager.delete(name))
            Unit
        }

    override suspend fun importZip(uri: String) =
        withContext(Dispatchers.IO) {
            val source = Uri.parse(uri)
            val result =
                source.inputStream(context).mapCatching { input ->
                    input.use { BottomBarSkinManager.extractImages(it).getOrThrow() }
                }
            val session = result.getOrElse { error ->
                // Manager exposes an untyped Result. Preserve the existing caller's import
                // distinction here.
                throw BottomBarSkinCatalogException(
                    if (error.message.orEmpty().contains("no ", true))
                        BottomBarSkinCatalogIssue.NoImages
                    else BottomBarSkinCatalogIssue.Invalid,
                    error,
                )
            }
            try {
                val name = runCatching {
                    FileDoc.fromUri(source, false).name
                }
                    .getOrNull()
                    .orEmpty()
                    .substringBeforeLast('.')
                BottomBarSkinStaged(
                    session,
                    if (name.codePointCount(0, name.length) <= 512) name
                    else BottomBarSkinFormat.sanitize(name),
                )
            } catch (error: Throwable) {
                BottomBarSkinManager.discardSession(session)
                throw error
            }
        }

    override suspend fun edit(name: String) =
        withContext(Dispatchers.IO) {
            BottomBarSkinStaged(BottomBarSkinManager.stageExisting(name).getOrThrow(), name, name)
        }

    override suspend fun zip(name: String) =
        withContext(Dispatchers.IO) {
            BottomBarSkinManager.cacheShareZip(name).getOrThrow().absolutePath
        }

    override suspend fun discard(session: String) =
        withContext(Dispatchers.IO) { BottomBarSkinManager.discardSession(session) }
}
