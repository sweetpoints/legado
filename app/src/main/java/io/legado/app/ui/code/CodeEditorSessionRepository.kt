package io.legado.app.ui.code

import android.content.Context
import android.util.AtomicFile
import io.legado.app.help.CacheManager
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface CodeEditorSessionRepository {
    suspend fun loadLaunch(launch: CodeEditorLaunch): CodeEditorSession

    suspend fun read(sessionId: String): CodeEditorSession?

    suspend fun write(sessionId: String, session: CodeEditorSession): Boolean

    suspend fun returnFile(sessionId: String, receiptId: String): String

    suspend fun prepareOutput(
        sessionId: String,
        session: CodeEditorSession,
    ): CodeEditorResultPayload

    suspend fun releaseOutput(path: String?)
}

internal class FileCodeEditorSessionRepository(
    context: Context,
    private val directory: File = File(context.applicationContext.filesDir, "code-editor-sessions"),
) : CodeEditorSessionRepository {
    private val context = context.applicationContext

    override suspend fun loadLaunch(launch: CodeEditorLaunch): CodeEditorSession =
        withContext(Dispatchers.IO) {
            val text =
                when {
                    launch.cacheKey != null ->
                        CacheManager.getFromMemory(launch.cacheKey) as? String ?: error("未获取到查看文本")
                    launch.textFile != null ->
                        CodeTextTransfer.read(context, launch.textFile) ?: error("未获取到待编辑文本")
                    else -> launch.text ?: error("未获取到待编辑文本")
                }
            currentCoroutineContext().ensureActive()
            CodeEditorSession(
                initialText = text,
                selection = CodeEditorSelection(launch.cursorPosition).bounded(text),
                title = launch.title,
                languageName = codeEditorLanguage(text, launch.languageName),
                writable = launch.cacheKey == null && !launch.readOnly,
                checkJavaScriptSyntax = launch.checkJavaScriptSyntax,
                showDebugSource = launch.showDebugSource,
                showLoginSource = launch.showLoginSource,
                returnUnchangedText = launch.returnUnchangedText,
                useTextFile = launch.useTextFile,
            )
        }

    private fun file(sessionId: String): AtomicFile {
        UUID.fromString(sessionId)
        return AtomicFile(File(directory, "$sessionId.json"))
    }

    private fun lock(sessionId: String): Mutex {
        val identity = File(directory, sessionId).absolutePath
        return locks[(identity.hashCode() and Int.MAX_VALUE) % locks.size]
    }

    private fun readFile(target: AtomicFile): CodeEditorSession? {
        val stream =
            try {
                target.openRead()
            } catch (_: FileNotFoundException) {
                return null
            }
        return stream.bufferedReader().use {
            GSON.fromJsonObject<CodeEditorSession>(it.readText()).getOrThrow()
        }
    }

    override suspend fun read(sessionId: String): CodeEditorSession? =
        withContext(Dispatchers.IO) {
            lock(sessionId).withLock { readFile(file(sessionId)) }
        }

    override suspend fun write(sessionId: String, session: CodeEditorSession): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(sessionId).withLock {
                val target = file(sessionId)
                val previous = readFile(target)
                // Full payload equality proves idempotent acceptance. A rejected generation
                // cannot authorize a platform return, or resurrect a closed editor's code.
                if (previous == session) return@withLock true
                if (previous?.finished == true || (previous?.revision ?: -1) >= session.revision)
                    return@withLock false
                check(directory.isDirectory || directory.mkdirs()) { "无法保存代码编辑草稿" }
                val output = target.startWrite()
                try {
                    output.write(GSON.toJson(session).toByteArray(Charsets.UTF_8))
                    target.finishWrite(output)
                } catch (error: Throwable) {
                    target.failWrite(output)
                    throw error
                }
                true
            }
        }

    override suspend fun returnFile(sessionId: String, receiptId: String): String =
        withContext(Dispatchers.IO) {
            UUID.fromString(sessionId)
            UUID.fromString(receiptId)
            File(context.cacheDir, "code-text-$sessionId-$receiptId.txt").absolutePath
        }

    override suspend fun prepareOutput(
        sessionId: String,
        session: CodeEditorSession,
    ): CodeEditorResultPayload =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(sessionId).withLock {
                if (readFile(file(sessionId)) != session || session.finished)
                    throw CodeEditorSessionConflict()
                val receipt = checkNotNull(session.returnReceipt)
                if (receipt.includeText && session.useTextFile) {
                    val path = checkNotNull(receipt.textFile)
                    check(path == returnFile(sessionId, receipt.id)) {
                        "Invalid editor result file owner"
                    }
                    val outputFile = AtomicFile(File(path))
                    // Once output IO accepts this fixed owner, finish atomically. Retrying uses the
                    // same filename and snapshot, never creates a second public transfer file.
                    val output = outputFile.startWrite()
                    try {
                        output.write(session.text.toByteArray(Charsets.UTF_8))
                        outputFile.finishWrite(output)
                    } catch (error: Throwable) {
                        outputFile.failWrite(output)
                        throw error
                    }
                }
                CodeEditorResultPayload(
                    cursorPosition = receipt.cursorPosition,
                    text = session.text.takeIf { receipt.includeText && !session.useTextFile },
                    textFile =
                        receipt.textFile.takeIf { receipt.includeText && session.useTextFile },
                    action = receipt.action,
                )
            }
        }

    override suspend fun releaseOutput(path: String?) =
        withContext(Dispatchers.IO) {
            CodeTextTransfer.delete(context, path)
        }

    private companion object {
        val locks = Array(64) { Mutex() }
    }
}
