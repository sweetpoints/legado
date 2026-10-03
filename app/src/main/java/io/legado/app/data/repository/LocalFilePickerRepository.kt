package io.legado.app.data.repository

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LocalFilePickerIssue {
    FileRequired,
    FolderNameRequired,
    DirectoryMissing,
    DirectoryUnreadable,
    OutsideRoot,
    InvalidFolderName,
    CreateFailed,
    SelectionInvalid,
}

class LocalFilePickerIssueException(val issue: LocalFilePickerIssue) :
    IllegalArgumentException(issue.name)

private fun requirePicker(condition: Boolean, issue: LocalFilePickerIssue) {
    if (!condition) throw LocalFilePickerIssueException(issue)
}

data class LocalFilePickerConfig(
    val root: String,
    val selectDirectory: Boolean = false,
    val extensions: List<String> = emptyList(),
    val showHidden: Boolean = false,
)

data class LocalFilePickerRow(
    val path: String,
    val name: String,
    val directory: Boolean,
    val enabled: Boolean,
)

data class LocalFilePickerCrumb(val path: String, val name: String)

data class LocalFilePickerSnapshot(
    val directory: String,
    val parent: String?,
    val crumbs: List<LocalFilePickerCrumb>,
    val rows: List<LocalFilePickerRow>,
)

interface LocalFilePickerRepository {
    suspend fun list(config: LocalFilePickerConfig, directory: String): LocalFilePickerSnapshot

    suspend fun create(config: LocalFilePickerConfig, directory: String, name: String)

    suspend fun validate(config: LocalFilePickerConfig, path: String): String
}

class DiskLocalFilePickerRepository : LocalFilePickerRepository {
    private fun checked(config: LocalFilePickerConfig, path: String): File {
        val root = File(config.root).canonicalFile
        val file = File(path).canonicalFile
        requirePicker(file.toPath().startsWith(root.toPath()), LocalFilePickerIssue.OutsideRoot)
        return file
    }

    override suspend fun list(config: LocalFilePickerConfig, directory: String) =
        withContext(Dispatchers.IO) {
            val file = checked(config, directory)
            requirePicker(file.isDirectory, LocalFilePickerIssue.DirectoryMissing)
            val root = File(config.root).canonicalFile
            val crumbs = mutableListOf<LocalFilePickerCrumb>()
            var current = file
            while (current != root) {
                crumbs.add(0, LocalFilePickerCrumb(current.path, current.name))
                current = checkNotNull(current.parentFile)
            }
            // showHidden was never a filtering switch in this public picker; preserve all visible
            // entries.
            val children =
                file.listFiles()
                    ?: throw LocalFilePickerIssueException(LocalFilePickerIssue.DirectoryUnreadable)
            val rows =
                children.sortedWith(compareBy({ it.isFile }, { it.name })).map { child ->
                    val inside = runCatching { checked(config, child.path) }.isSuccess
                    LocalFilePickerRow(
                        child.path,
                        child.name,
                        child.isDirectory,
                        inside &&
                            (child.isDirectory ||
                                !config.selectDirectory && allowed(config, child.path)),
                    )
                }
            LocalFilePickerSnapshot(
                file.path,
                file.parentFile?.path?.takeIf { file != root },
                crumbs,
                rows,
            )
        }

    override suspend fun create(config: LocalFilePickerConfig, directory: String, name: String) =
        withContext(Dispatchers.IO) {
            val parent = checked(config, directory)
            requirePicker(parent.isDirectory, LocalFilePickerIssue.DirectoryMissing)
            requirePicker(name.isNotBlank(), LocalFilePickerIssue.FolderNameRequired)
            requirePicker(!name.contains(0.toChar()), LocalFilePickerIssue.InvalidFolderName)
            val child = File(parent, name.trim()).canonicalFile
            requirePicker(
                child.toPath().startsWith(parent.toPath()) && child != parent,
                LocalFilePickerIssue.InvalidFolderName,
            )
            checked(config, child.path)
            requirePicker(child.mkdir(), LocalFilePickerIssue.CreateFailed)
        }

    override suspend fun validate(config: LocalFilePickerConfig, path: String) =
        withContext(Dispatchers.IO) {
            val file = checked(config, path)
            requirePicker(
                if (config.selectDirectory) file.isDirectory
                else file.isFile && allowed(config, path),
                LocalFilePickerIssue.SelectionInvalid,
            )
            file.path
        }

    companion object {
        fun allowed(config: LocalFilePickerConfig, path: String): Boolean {
            val dot = path.lastIndexOf('.')
            val extension = if (dot >= 0) path.substring(dot + 1) else "ext"
            return config.extensions.isEmpty() || extension in config.extensions
        }
    }
}
