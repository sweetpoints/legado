package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.model.browser.BrowserRequest
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Keep internal data class PreparedBrowserNavigation(val request: BrowserRequest)

internal interface BrowserNavigationStore {
    suspend fun prepare(request: BrowserRequest): String

    suspend fun prepare(ticket: String, request: BrowserRequest)

    suspend fun read(ticket: String): BrowserRequest

    suspend fun abandon(ticket: String)
}

/** Stores full browser requests privately while Activities carry only an opaque UUID. */
internal class AppBrowserNavigationStore(context: Context) : BrowserNavigationStore {
    private val directory = File(context.applicationContext.filesDir, "browser-navigation")

    override suspend fun prepare(request: BrowserRequest): String {
        val ticket = UUID.randomUUID().toString()
        prepare(ticket, request)
        return ticket
    }

    override suspend fun prepare(ticket: String, request: BrowserRequest) {
        locked(ticket) {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建网页导航目录" }
            writeFile(file(ticket), GSON.toJson(PreparedBrowserNavigation(request)))
        }
    }

    override suspend fun read(ticket: String): BrowserRequest =
        locked(ticket) {
            val target = file(ticket)
            check(target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()) {
                "网页请求已丢失，请重试"
            }
            val prepared =
                GSON.fromJson(
                    target.readFully().toString(Charsets.UTF_8),
                    PreparedBrowserNavigation::class.java,
                ) ?: error("网页请求损坏")
            prepared.request
        }

    override suspend fun abandon(ticket: String) = locked(ticket) { file(ticket).delete() }

    private fun file(ticket: String): AtomicFile {
        require(UUID.fromString(ticket).toString() == ticket)
        return AtomicFile(File(directory, "$ticket.json"))
    }

    private fun writeFile(target: AtomicFile, text: String) {
        val output = target.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (error: Throwable) {
            target.failWrite(output)
            throw error
        }
    }

    private suspend fun <T> locked(ticket: String, block: () -> T): T {
        require(UUID.fromString(ticket).toString() == ticket)
        val canonical = File(directory, "$ticket.json").canonicalPath
        return stripes[(canonical.hashCode() and Int.MAX_VALUE) % stripes.size].withLock { block() }
    }

    private companion object {
        val stripes = Array(64) { Mutex() }
    }
}
