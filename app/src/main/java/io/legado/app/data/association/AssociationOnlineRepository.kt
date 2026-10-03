package io.legado.app.data.association

import android.content.Context
import android.net.Uri
import io.legado.app.constant.AppConst
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.jsonPath
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType

/** Transient download projection; byte arrays and complete URLs never enter SavedState. */
data class AssociationOnlinePayload(
    val importType: String? = null,
    val source: String? = null,
    val readConfigFile: String? = null,
)

interface AssociationOnlineRepository {
    suspend fun determine(ticket: String, url: String): AssociationOnlinePayload

    suspend fun readConfig(ticket: String, url: String): AssociationOnlinePayload

    suspend fun text(url: String): String
}

class HttpAssociationOnlineRepository(
    context: Context,
    private val sessions: FileAssociationSessionRepository =
        FileAssociationSessionRepository(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AssociationOnlineRepository {
    private suspend fun response(url: String) = okHttpClient.newCallResponseBody {
        if (url.endsWith("#requestWithoutUA")) {
            url(url.substringBeforeLast("#requestWithoutUA"))
            header(AppConst.UA_NAME, "null")
        } else {
            url(url)
        }
    }

    override suspend fun text(url: String): String =
        withContext(dispatcher) {
            response(url).decompressed().text("utf-8")
        }

    override suspend fun readConfig(ticket: String, url: String): AssociationOnlinePayload =
        withContext(dispatcher) {
            val bytes = response(url).use { it.bytes() }
            currentCoroutineContext().ensureActive()
            storeReadConfig(ticket, bytes)
        }

    override suspend fun determine(ticket: String, url: String): AssociationOnlinePayload =
        withContext(dispatcher) {
            val (isReadConfig, bytes) =
                response(url).use { body ->
                    val isBinary =
                        body.contentType() == "application/zip".toMediaType() ||
                            body.contentType() == "application/octet-stream".toMediaType()
                    isBinary to body.bytes()
                }
            currentCoroutineContext().ensureActive()
            if (isReadConfig) {
                storeReadConfig(ticket, bytes)
            } else {
                storeImportJson(ticket, bytes)
            }
        }

    private suspend fun storeReadConfig(
        ticket: String,
        bytes: ByteArray,
    ): AssociationOnlinePayload {
        val filename = "read-config-${UUID.randomUUID()}.data"
        sessions.writeBytes(ticket, filename, bytes)
        return AssociationOnlinePayload(readConfigFile = filename)
    }

    private suspend fun storeImportJson(
        ticket: String,
        bytes: ByteArray,
    ): AssociationOnlinePayload {
        // Finish cancellable HTTP before taking the private-session gate. The gate protects only
        // local payload publication, so close cannot wait for an unrelated remote server.
        val filename = "online-import-${UUID.randomUUID()}.json"
        sessions.writeBytes(ticket, filename, bytes)
        return sessions.withOwnedDirectory(ticket) { directory ->
            val file = File(directory, filename)
            val map =
                file.inputStream().use {
                    jsonPath.parse(it).read<Map<String, *>>("$[0]")
                } ?: file.inputStream().use { jsonPath.parse(it).read<Map<String, *>>("$") }
            val type = associationJsonImportType(map) ?: error("格式不对")
            AssociationOnlinePayload(importType = type, source = Uri.fromFile(file).toString())
        }
    }
}
