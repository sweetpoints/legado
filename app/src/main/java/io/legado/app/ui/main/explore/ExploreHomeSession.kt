package io.legado.app.ui.main.explore

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID

@Keep
internal data class ExploreHomeSession(
    val query: String = "",
    val expandedUrl: String? = null,
    val values: Map<String, Map<String, String>> = emptyMap(),
    val deleteUrl: String? = null,
    val errorText: String? = null,
    val effect: ExploreHomeEffect? = null,
    val receipts: Set<String> = emptySet(),
    val pendingOperation: Boolean = false,
)

internal interface ExploreHomeSessionStorage {
    fun read(): ExploreHomeSession

    fun write(snapshot: ExploreHomeSession)

    fun delete()
}

internal class FileExploreHomeSessionStorage(context: Context, token: String) :
    ExploreHomeSessionStorage {
    private val directory = File(context.filesDir, "explore-home-sessions")
    private val file = AtomicFile(File(directory, "${UUID.fromString(token)}.json"))

    override fun read(): ExploreHomeSession {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            return ExploreHomeSession()
        return file.openRead().bufferedReader().use {
            GSON.fromJson(it, ExploreHomeSession::class.java)
        }
    }

    override fun write(snapshot: ExploreHomeSession) {
        directory.mkdirs()
        val output = file.startWrite()
        try {
            output.write(GSON.toJson(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    override fun delete() {
        file.delete()
    }
}
