package io.legado.app.model.remote

import io.legado.app.utils.AlphanumComparator

/** Detached values; credentials and native WebDAV clients never enter Compose state. */
internal data class RemoteLibraryConnection(val id: String, val root: String, val defaultServer: Boolean, val serverId: Long?)
internal data class RemoteLibraryEntry(val id: String, val name: String, val path: String, val size: Long,
    val modified: Long, val type: String, val onShelf: Boolean) {
    val directory: Boolean get() = type == "folder"
    val checkable: Boolean get() = !directory && !onShelf
}
internal enum class RemoteLibrarySort { Modified, Name }
internal fun projectRemoteLibrary(entries: List<RemoteLibraryEntry>, query: String, sort: RemoteLibrarySort,
    ascending: Boolean): List<RemoteLibraryEntry> {
    // Legacy search is filename.contains with case-sensitive matching; directories remain first in both directions.
    val visible = if (query.isBlank()) entries else entries.filter { it.name.contains(query) }
    return visible.sortedWith { first, second ->
        val kind = compareValues(!first.directory, !second.directory)
        if (kind != 0) kind else {
            val value = when (sort) { RemoteLibrarySort.Name -> AlphanumComparator.compare(first.name, second.name)
                RemoteLibrarySort.Modified -> compareValues(first.modified, second.modified) }
            if (ascending) value else -value
        }
    }
}
