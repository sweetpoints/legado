package io.legado.app.data.association

import android.content.Context
import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.storage.selectedBackupFileNames
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.inputStream
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isFileScheme
import io.legado.app.utils.jsonPath
import io.legado.app.utils.looksLikeJson
import io.legado.app.utils.openInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Complete metadata and URI values stay in the private session, never in saved instance state. */
data class AssociationFileInspection(
    val staging: AssociationStagingResult? = null,
    val finished: Boolean = false,
    val openSingleBook: Boolean = false,
    val importType: String? = null,
    val source: String? = null,
    val onlineUri: String? = null,
    val unsupportedUri: String? = null,
    val unsupportedName: String? = null,
)

interface AssociationFileRepository {
    suspend fun inspect(ticket: String, input: AssociationInput): AssociationFileInspection
}

class LocalAssociationFileRepository(
    context: Context,
    private val sessions: FileAssociationSessionRepository =
        FileAssociationSessionRepository(context),
    private val localBooks: AssociationLocalBooksRepository =
        AssociationLocalBooksRepository(sessions),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AssociationFileRepository {
    private val applicationContext = context.applicationContext

    override suspend fun inspect(
        ticket: String,
        input: AssociationInput,
    ): AssociationFileInspection =
        withContext(dispatcher) {
            when (input.kind) {
                AssociationInputKind.SharedUris -> {
                    require(
                        input.uris.isNotEmpty() &&
                            input.uris.all { Uri.parse(it).isContentScheme() }
                    )
                    AssociationFileInspection(staging = localBooks.stage(ticket, input.uris))
                }
                AssociationInputKind.SharedText -> inspectText(ticket, checkNotNull(input.text))
                AssociationInputKind.SharedUri -> {
                    val uri = Uri.parse(input.uris.single())
                    require(uri.isContentScheme())
                    // An exported provider can allow reading without an explicit URI grant.
                    uri.inputStream(applicationContext).getOrThrow().use {}
                    inspectUri(ticket, uri, shared = true)
                }
                AssociationInputKind.View -> {
                    val uri = input.uris.singleOrNull()
                    if (uri == null) AssociationFileInspection(finished = true)
                    else inspectUri(ticket, Uri.parse(uri), shared = false)
                }
                AssociationInputKind.Invalid -> error("格式不对")
            }
        }

    private suspend fun inspectText(ticket: String, text: String): AssociationFileInspection {
        associationSharedImportUrl(text)?.let { url ->
            return AssociationFileInspection(
                onlineUri =
                    Uri.Builder()
                        .scheme("legado")
                        .authority("import")
                        .appendPath("auto")
                        .appendQueryParameter("src", url)
                        .build()
                        .toString()
            )
        }
        val filename = "shared-text-${UUID.randomUUID()}.json"
        sessions.writeBytes(ticket, filename, text.toByteArray())
        return sessions.withOwnedDirectory(ticket) { directory ->
            inspectJson(Uri.fromFile(File(directory, filename)))
        }
    }

    private suspend fun inspectUri(
        ticket: String,
        uri: Uri,
        shared: Boolean,
    ): AssociationFileInspection {
        if (!uri.isContentScheme() && !uri.isFileScheme())
            return AssociationFileInspection(onlineUri = uri.toString())
        val document = FileDoc.fromUri(uri, false)
        if (document.name.matches(AppPattern.archiveFileRegex)) {
            val backupNames = selectedBackupFileNames { true }.toSet()
            val entries =
                if (document.name.endsWith(".zip", true)) {
                    ArchiveUtils.getArchiveFilesName(document) {
                        it in backupNames || it.matches(AppPattern.bookFileRegex)
                    }
                } else emptyList()
            // Backup archives contain records/media; a local book entry keeps the book preview
            // path.
            if (
                entries.any { it in backupNames } &&
                    entries.none { it.matches(AppPattern.bookFileRegex) }
            ) {
                return AssociationFileInspection(importType = "backup", source = uri.toString())
            }
            return AssociationFileInspection(
                staging = localBooks.stage(ticket, listOf(uri.toString()))
            )
        }
        val looksLikeJson = runCatching {
            document.openInputStream().getOrThrow().use { it.looksLikeJson() }
        }
            .getOrDefault(false)
        currentCoroutineContext().ensureActive()
        if (looksLikeJson) {
            try {
                return inspectJson(uri)
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                // An unrecognized valid record reports the old format error; a failed JSON
                // parser falls through to the original extension/unsupported-file handling.
                if (failure.message == "格式不对") throw failure
                AppLog.put("尝试导入为JSON文件失败\n${failure.localizedMessage}", failure)
            }
        }
        if (document.name.matches(AppPattern.jsFileRegex))
            return AssociationFileInspection(importType = "bookSource", source = uri.toString())
        if (document.name.matches(AppPattern.bookFileRegex))
            return AssociationFileInspection(
                staging = localBooks.stage(ticket, listOf(uri.toString())),
                openSingleBook = !shared,
            )
        return AssociationFileInspection(
            unsupportedUri = uri.toString(),
            unsupportedName = document.name,
        )
    }

    private fun inspectJson(uri: Uri): AssociationFileInspection {
        val record =
            uri.inputStream(applicationContext).getOrThrow().use {
                jsonPath.parse(it).read<Map<String, *>>("$[0]")
            }
                ?: uri.inputStream(applicationContext).getOrThrow().use {
                    jsonPath.parse(it).read<Map<String, *>>("$")
                }
        val type = associationJsonImportType(record) ?: error("格式不对")
        return AssociationFileInspection(importType = type, source = uri.toString())
    }
}
