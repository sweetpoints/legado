package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.model.browser.BrowserSession
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AppBrowserSessionStore(context: Context) : BrowserSessionStore {
    private val directory = File(context.applicationContext.filesDir, "browser-sessions")

    override suspend fun claim(session: String, owner: String) =
        locked(session) {
            require(UUID.fromString(owner).toString() == owner)
            open(session)
            writeText(ownerFile(session), owner)
        }

    override suspend fun read(session: String, owner: String) =
        locked(session) {
            open(session)
            checkOwner(session, owner)
            readFile(target(session))
        }

    override suspend fun create(session: String, owner: String, seed: BrowserSession) =
        locked(session) {
            open(session)
            checkOwner(session, owner)
            readFile(target(session)) ?: seed.also { writeFile(target(session), GSON.toJson(it)) }
        }

    override suspend fun write(session: String, owner: String, snapshot: BrowserSession): Boolean =
        locked(session) {
            open(session)
            if (!hasOwner(session, owner)) return@locked false
            val previous = readFile(target(session)) ?: throw BrowserSessionClosedException()
            when {
                snapshot.revision > previous.revision -> {
                    writeFile(target(session), GSON.toJson(snapshot))
                    true
                }
                snapshot.revision == previous.revision -> snapshot == previous
                else -> false
            }
        }

    override suspend fun release(session: String, owner: String) =
        locked(session) {
            if (!hasOwner(session, owner)) return@locked
            val marker = marker(session)
            if (!marker.exists() && !File(marker.path + ".bak").exists())
                writeFile(marker, "closed")
            val target = target(session)
            AtomicFile(target).delete()
            ownerFile(session).delete()
            check(
                listOf(
                        target,
                        File(target.path + ".bak"),
                        File(target.path + ".new"),
                        ownerFile(session).baseFile,
                        File(ownerFile(session).baseFile.path + ".bak"),
                        File(ownerFile(session).baseFile.path + ".new"),
                    )
                    .none { it.exists() }
            ) {
                "无法清理网页会话"
            }
        }

    private fun open(session: String) {
        val marker = marker(session)
        if (marker.exists() || File(marker.path + ".bak").exists())
            throw BrowserSessionClosedException()
    }

    private fun readFile(file: File): BrowserSession? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return GSON.fromJson(
            AtomicFile(file).readFully().toString(Charsets.UTF_8),
            BrowserSession::class.java,
        ) ?: error("网页会话损坏")
    }

    private fun writeFile(file: File, text: String) {
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true) {
            "无法创建网页会话目录"
        }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun target(session: String) = File(directory, "$session.json")

    private fun marker(session: String) = File(directory, "$session.closed")

    private fun ownerFile(session: String) = AtomicFile(File(directory, "$session.owner"))

    private fun hasOwner(session: String, owner: String): Boolean = runCatching {
        UUID.fromString(owner).toString() == owner &&
            ownerFile(session).readFully().toString(Charsets.UTF_8) == owner
    }
        .getOrDefault(false)

    private fun checkOwner(session: String, owner: String) {
        if (!hasOwner(session, owner)) throw BrowserSessionClosedException()
    }

    private fun writeText(target: AtomicFile, text: String) {
        val parent = target.baseFile.parentFile
        check(parent?.mkdirs() == true || parent?.isDirectory == true) { "无法创建网页会话目录" }
        val output = target.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (error: Throwable) {
            target.failWrite(output)
            throw error
        }
    }

    private suspend fun <T> locked(session: String, block: () -> T): T {
        require(session.matches(Regex("[A-Za-z0-9-]+")))
        return stripes[(target(session).canonicalPath.hashCode() and Int.MAX_VALUE) % stripes.size]
            .withLock { block() }
    }

    private companion object {
        val stripes = Array(64) { Mutex() }
    }
}
