package io.legado.app.data.preferences

import android.content.Context
import android.util.AtomicFile
import com.google.gson.JsonParser
import io.legado.app.model.settings.OtherSettingsDraft
import io.legado.app.utils.GSON
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface OtherSettingsDraftRepository {
    suspend fun open(session: String): OtherSettingsDraft

    suspend fun write(session: String, draft: OtherSettingsDraft)

    suspend fun release(session: String)
}

/**
 * Private credentials, form text and task payloads never enter SavedState; a closed owner cannot
 * resurrect files.
 */
internal class FileOtherSettingsDraftRepository(context: Context) : OtherSettingsDraftRepository {
    private val directory = File(context.applicationContext.filesDir, "other-settings-drafts")

    private fun file(session: String): File {
        require(session.matches(Regex("[A-Za-z0-9-]{1,64}")))
        return File(directory, "$session.json")
    }

    private fun lock(file: File) =
        locks[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % locks.size]

    private fun closed(file: File) =
        File(file.path + ".closed").let { it.exists() || File(it.path + ".bak").exists() }

    private fun read(file: File): OtherSettingsDraft =
        AtomicFile(file).openRead().bufferedReader().use {
            decodeOtherSettingsDraft(it.readText())
        }

    private fun save(file: File, value: OtherSettingsDraft) {
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(GSON.toJson(value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    override suspend fun open(session: String) =
        withContext(Dispatchers.IO) {
            val file = file(session)
            lock(file).withLock {
                check(!closed(file)) { "Other settings draft closed" }
                if (file.exists() || File(file.path + ".bak").exists()) read(file)
                else OtherSettingsDraft().also { save(file, it) }
            }
        }

    override suspend fun write(session: String, draft: OtherSettingsDraft): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = file(session)
            lock(file).withLock {
                check(!closed(file) && (file.exists() || File(file.path + ".bak").exists())) {
                    "Other settings draft closed"
                }
                if (draft.revision >= read(file).revision) save(file, draft)
            }
        }

    override suspend fun release(session: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = file(session)
            lock(file).withLock {
                check(directory.isDirectory || directory.mkdirs())
                val marker = AtomicFile(File(file.path + ".closed"))
                val output = marker.startWrite()
                try {
                    output.write("closed".toByteArray())
                    marker.finishWrite(output)
                } catch (error: Throwable) {
                    marker.failWrite(output)
                    throw error
                }
                AtomicFile(file).delete()
                check(
                    !file.exists() &&
                        !File(file.path + ".bak").exists() &&
                        !File(file.path + ".new").exists()
                ) {
                    "Unable to release other settings draft"
                }
            }
        }

    private companion object {
        val locks = Array(64) { Mutex() }
    }
}

/** Old unfinished Web Server controls cannot be applied after the feature has been removed. */
internal fun decodeOtherSettingsDraft(json: String): OtherSettingsDraft {
    val value = JsonParser.parseString(json).asJsonObject
    val removedControls = setOf("WebPort", "WebWake")
    if (value.get("editor")?.takeIf { it.isJsonPrimitive }?.asString in removedControls) {
        value.remove("editor")
        value.addProperty("text", "")
        value.addProperty("selectionStart", 0)
        value.addProperty("selectionEnd", 0)
    }
    val mutation = value.get("mutation")?.takeIf { it.isJsonObject }?.asJsonObject
    if (mutation?.get("key")?.asString in removedControls) {
        value.remove("mutation")
    } else {
        mutation?.get("effects")?.takeIf { it.isJsonArray }?.asJsonArray?.let { effects ->
            for (index in effects.size() - 1 downTo 0) {
                val effect = effects[index]
                if (effect.isJsonPrimitive && effect.asString == "RestartWeb") effects.remove(index)
            }
        }
    }
    value.get("effects")?.takeIf { it.isJsonArray }?.asJsonArray?.let { effects ->
        for (index in effects.size() - 1 downTo 0) {
            val effect = effects[index]
            if (effect.isJsonObject && effect.asJsonObject.get("effect")?.asString == "RestartWeb") {
                effects.remove(index)
            }
        }
    }
    return GSON.fromJson(value, OtherSettingsDraft::class.java)
}
