package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Immutable editor snapshot; the shared Room/Parcelable entity stays at the repository boundary. */
data class BookGroupEditorSnapshot(val id: Long, val name: String = "", val cover: String? = null,
    val order: Int = 0, val enableRefresh: Boolean = true, val show: Boolean = true,
    val bookSort: Int = -1, val onlyUpdateRead: Boolean = false) {
    val canDelete get() = id > 0 || id == Long.MIN_VALUE
    fun entity() = BookGroup(id, name, cover, order, enableRefresh, show, bookSort, onlyUpdateRead)
    companion object { fun from(group: BookGroup) = BookGroupEditorSnapshot(group.groupId, group.groupName, group.cover, group.order, group.enableRefresh, group.show, group.bookSort, group.onlyUpdateRead) }
}
interface BookGroupEditorRepository {
    suspend fun load(id: Long): BookGroupEditorSnapshot?
    suspend fun save(draft: BookGroupEditorSnapshot, existing: Boolean): BookGroupEditorSnapshot
    suspend fun delete(id: Long)
    suspend fun importCover(uri: String): String
}
class RoomBookGroupEditorRepository(context: Context, private val database: AppDatabase = appDb) : BookGroupEditorRepository {
    private val context = context.applicationContext
    override suspend fun load(id: Long) = withContext(Dispatchers.IO) { database.bookGroupDao.getByID(id)?.let(BookGroupEditorSnapshot::from) }
    override suspend fun save(draft: BookGroupEditorSnapshot, existing: Boolean) = withContext(Dispatchers.IO) {
        require(draft.name.isNotEmpty()) { "分组名称不能为空" }
        require(draft.bookSort in -1..5)
        database.withTransaction {
            if (existing) {
                val current = database.bookGroupDao.getByID(draft.id) ?: error("分组不存在")
                val updated = draft.copy(order = current.order, show = current.show)
                database.bookGroupDao.update(updated.entity())
                updated
            } else {
                check(database.bookGroupDao.canAddGroup) { "分组已达上限(63个)" }
                val id = database.bookGroupDao.getUnusedId()
                check(id > 0 && database.bookGroupDao.getByID(id) == null) { "分组已达上限(63个)" }
                val added = draft.copy(id = id, order = database.bookGroupDao.maxOrder + 1, show = true)
                clearBooksGroup(id)
                database.bookGroupDao.insert(added.entity())
                added
            }
        }
    }
    override suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        require(id > 0 || id == Long.MIN_VALUE) { "系统分组不能删除" }
        database.withTransaction {
            val group = database.bookGroupDao.getByID(id) ?: error("分组不存在")
            database.bookGroupDao.delete(group)
            clearBooksGroup(id)
        }
    }
    private fun clearBooksGroup(id: Long) {
        // A bitwise mask also clears the legacy sign-bit group; the DAO's >0 predicate cannot.
        database.openHelper.writableDatabase.execSQL("UPDATE books SET `group` = `group` & ? WHERE (`group` & ?) != 0", arrayOf(id.inv(), id))
    }
    override suspend fun importCover(uri: String): String = withContext(Dispatchers.IO) {
        val source = Uri.parse(uri)
        if (source.scheme?.lowercase() in listOf("http", "https")) return@withContext uri
        val local = if (source.scheme == "content") null else (if (source.scheme == "file") source.path else RealPathUtil.getPath(context, source))?.let(::File)
        val name = if (source.scheme == "content") DocumentFile.fromSingleUri(context, source)?.name else local?.name
        check(!name.isNullOrEmpty()) { "未获取到文件" }
        val suffix = if (name.contains(".9.png", true)) ".9.png" else "." + name.substringAfterLast('.')
        val directory = File(context.externalFiles, "covers").apply { check(isDirectory || mkdirs()) }
        val temporary = File.createTempFile("group_cover_", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("MD5")
            (if (source.scheme == "content") checkNotNull(context.contentResolver.openInputStream(source)) else checkNotNull(local).inputStream()).use { input -> DigestOutputStream(temporary.outputStream(), digest).use { output -> input.copyTo(output) } }
            currentCoroutineContext().ensureActive()
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val cover = File(directory, hash + suffix)
            if (cover.exists()) temporary.delete() else check(temporary.renameTo(cover)) { "无法保存图片" }
            cover.absolutePath
        } finally { withContext(NonCancellable + Dispatchers.IO) { temporary.delete() } }
    }
}
