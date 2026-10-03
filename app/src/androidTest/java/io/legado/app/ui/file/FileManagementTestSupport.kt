package io.legado.app.ui.file

import io.legado.app.data.repository.*
import kotlinx.coroutines.*

internal class ManagementFiles : FileManagementRepository {
    val deleted = mutableListOf<String>()
    var opens = 0
    val values =
        mutableMapOf(
            "/root" to
                ManagedDirectory(
                    "/root",
                    "/root",
                    listOf(ManagedFileCrumb("/root", "root")),
                    listOf(
                        ManagedFile("/root/folder", "folder", ManagedFileKind.Directory),
                        ManagedFile("/root/root.txt", "root.txt", ManagedFileKind.File),
                    ),
                ),
            "/root/folder" to
                ManagedDirectory(
                    "/root",
                    "/root/folder",
                    listOf(
                        ManagedFileCrumb("/root", "root"),
                        ManagedFileCrumb("/root/folder", "folder"),
                    ),
                    listOf(
                        ManagedFile("/root", "..", ManagedFileKind.Parent),
                        ManagedFile("/root/folder/Alpha.txt", "Alpha.txt", ManagedFileKind.File),
                        ManagedFile("/root/folder/alpha.txt", "alpha.txt", ManagedFileKind.File),
                    ),
                ),
        )

    override suspend fun list(directory: String?) = values[directory ?: "/root"]!!

    override suspend fun delete(path: String): Boolean {
        deleted += path
        values.replaceAll { _, value ->
            value.copy(entries = value.entries.filter { it.path != path })
        }
        return true
    }

    override suspend fun open(path: String): String {
        opens++
        return "content://provider/$path"
    }
}

internal class ManagementDrafts : FileManagementDraftRepository {
    val records = mutableMapOf<String, FileManagementDraft>()
    val released = mutableSetOf<String>()
    var claimGate: CompletableDeferred<Unit>? = null
    var failClaim = false

    override suspend fun read(ticket: String) = records[ticket]

    override suspend fun write(ticket: String, draft: FileManagementDraft) {
        check(ticket !in released)
        if (draft.navigation == null && records[ticket]?.navigation != null) {
            if (failClaim) error("claim failed")
            val gate = claimGate
            claimGate = null
            gate?.let { withContext(NonCancellable) { it.await() } }
        }
        check(ticket !in released)
        if ((records[ticket]?.revision ?: -1) <= draft.revision) records[ticket] = draft
    }

    override suspend fun release(ticket: String) {
        released += ticket
        records.remove(ticket)
    }
}
