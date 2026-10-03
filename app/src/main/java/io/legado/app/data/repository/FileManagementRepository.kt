package io.legado.app.data.repository

import android.content.Context
import androidx.core.content.FileProvider
import io.legado.app.constant.AppConst
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ManagedFileKind {
    Parent,
    Directory,
    File,
}

data class ManagedFile(val path: String, val name: String, val kind: ManagedFileKind)

data class ManagedFileCrumb(val path: String, val name: String)

data class ManagedDirectory(
    val root: String?,
    val directory: String?,
    val crumbs: List<ManagedFileCrumb>,
    val entries: List<ManagedFile>,
)

interface FileManagementRepository {
    suspend fun list(directory: String?): ManagedDirectory

    suspend fun delete(path: String): Boolean

    suspend fun open(path: String): String
}

/** Only the original application-owned external root is managed. Delete stays nonrecursive. */
open class LocalFileManagementRepository(private val rootDirectory: () -> File?) :
    FileManagementRepository {
    private val commands = Mutex()

    private fun root() = rootDirectory()?.canonicalFile

    protected fun resolve(path: String): File {
        val root = root() ?: throw IOException("File storage is unavailable")
        val file = File(path).absoluteFile.normalize()
        val actual = file.canonicalFile
        require(actual == root || actual.path.startsWith(root.path + File.separator)) {
            "File is outside the managed root"
        }
        return file
    }

    override suspend fun list(directory: String?): ManagedDirectory =
        withContext(Dispatchers.IO) {
            val root =
                root() ?: return@withContext ManagedDirectory(null, null, emptyList(), emptyList())
            val file = (if (directory == null) root else resolve(directory)).canonicalFile
            if (!file.isDirectory) throw IOException("Directory is unavailable")
            val children = file.listFiles() ?: throw IOException("Cannot read directory")
            val entries =
                children.sortedWith(compareBy<File>({ it.isFile }, { it.name })).map { child ->
                    ManagedFile(
                        child.absolutePath,
                        child.name,
                        if (child.isDirectory) ManagedFileKind.Directory else ManagedFileKind.File,
                    )
                }
            val parent =
                if (file.canonicalFile == root) emptyList()
                else
                    listOf(
                        ManagedFile(file.parentFile!!.absolutePath, "..", ManagedFileKind.Parent)
                    )
            val crumbs = mutableListOf(ManagedFileCrumb(root.path, "root"))
            var current = root
            file
                .relativeTo(root)
                .path
                .takeUnless { it.isEmpty() }
                ?.split(File.separatorChar)
                ?.forEach { name ->
                    current = File(current, name)
                    crumbs += ManagedFileCrumb(current.absolutePath, name)
                }
            ManagedDirectory(root.path, file.absolutePath, crumbs.toList(), parent + entries)
        }

    override suspend fun delete(path: String): Boolean =
        withContext(Dispatchers.IO) {
            commands.withLock {
                val file = resolve(path)
                require(file.canonicalFile != root()) { "Cannot delete the managed root" }
                file.delete()
            }
        }

    override suspend fun open(path: String): String =
        withContext(Dispatchers.IO) {
            val file = resolve(path)
            if (!file.exists() || file.isDirectory) throw IOException("File is unavailable")
            file.toURI().toString()
        }
}

private fun applicationRoot(context: Context): () -> File? {
    val application = context.applicationContext
    return { application.getExternalFilesDir(null)?.parentFile }
}

class AppFileManagementRepository(context: Context) :
    LocalFileManagementRepository(applicationRoot(context)) {
    private val application = context.applicationContext

    override suspend fun open(path: String): String =
        withContext(Dispatchers.IO) {
            super.open(path)
            FileProvider.getUriForFile(application, AppConst.authority, resolve(path)).toString()
        }
}
