package io.legado.app.data.association

import android.net.Uri
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.AppPattern.jsFileRegex
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.jsonPath
import io.legado.app.utils.looksLikeJson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class AssociationStagingResult(
    val previews: List<AssociationBookPreview> = emptyList(),
    val importType: String? = null,
    val importSource: String? = null,
    val mixedTypes: Boolean = false,
)

/** Local extraction and full metadata projection hold a cancellable lease on their private UUID. */
class AssociationLocalBooksRepository(private val sessions: FileAssociationSessionRepository) {
    suspend fun stage(ticket: String, uris: List<String>): AssociationStagingResult =
        sessions.withOwnedDirectory(ticket) { directory ->
            val staging = File(directory, "staged-${UUID.randomUUID()}")
            val files = collectAssociationImportFiles(uris.map(Uri::parse), staging)
            currentCoroutineContext().ensureActive()
            val dataFiles = files.mapNotNull { file ->
                val type =
                    if (file.name.matches(jsFileRegex)) "bookSource"
                    else {
                        runCatching {
                            if (!file.inputStream().looksLikeJson()) return@runCatching null
                            val record =
                                file.inputStream().use {
                                    jsonPath.parse(it).read<Map<String, *>>("$[0]")
                                } ?: file.inputStream().use { jsonPath.parse(it).read("$") }
                            associationJsonImportType(record)
                        }
                            .getOrNull()
                    }
                type?.let { it to file }
            }
            val bookFiles = files.filter { file ->
                file.name.matches(bookFileRegex) && dataFiles.none { it.second == file }
            }
            if (dataFiles.isNotEmpty()) {
                if (bookFiles.isNotEmpty() || dataFiles.map { it.first }.distinct().size != 1) {
                    return@withOwnedDirectory AssociationStagingResult(mixedTypes = true)
                }
                val type = dataFiles.first().first
                val source =
                    if (dataFiles.size == 1) dataFiles.single().second
                    else {
                        mergeDataFiles(staging, type, dataFiles.map { it.second })
                    }
                currentCoroutineContext().ensureActive()
                AssociationStagingResult(
                    importType = type,
                    importSource = Uri.fromFile(source).toString(),
                )
            } else {
                val previews = previewAssociationLocalBooks(bookFiles)
                currentCoroutineContext().ensureActive()
                AssociationStagingResult(previews = previews)
            }
        }

    private suspend fun mergeDataFiles(staging: File, type: String, files: List<File>): File {
        val merged = JsonArray()
        for (source in files) {
            currentCoroutineContext().ensureActive()
            val json =
                if (source.name.matches(jsFileRegex)) {
                    GSON.toJsonTree(
                        JsSourceConfig.extract(source.readText(), currentCoroutineContext())
                    )
                } else source.reader().use { GSON.fromJson(it, JsonElement::class.java) }
            val records =
                if (
                    type == "highlightRule" &&
                        json.isJsonObject &&
                        json.asJsonObject.get("type")?.asString == HighlightRuleFile.TYPE
                ) {
                    json.asJsonObject.getAsJsonArray("rules")
                } else json
            if (records.isJsonArray) records.asJsonArray.forEach(merged::add)
            else merged.add(records)
        }
        return File(staging, "import-data.json").apply { writeText(merged.toString()) }
    }
}
