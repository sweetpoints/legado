package io.legado.app.utils

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceBook
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.CrashHandler
import io.legado.app.help.RegexJsExtensions
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.model.sourceEngine.V8ScriptExecutor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import splitties.init.appCtx

private val handler by lazy { buildMainHandler() }

/** 带有超时检测的正则替换 */
@OptIn(ExperimentalCoroutinesApi::class)
fun CharSequence.replace(
    name: String,
    regex: Regex,
    replacement: String,
    timeout: Long,
    chapter: BookChapter? = null,
    book: ReplaceBook? = null,
    includeContentInTimeoutMessage: Boolean = true,
): String {
    val charSequence = this@replace
    val isJs = replacement.startsWith("@js:")
    val replacement1 = if (isJs) replacement.substring(4) else replacement
    val reJsExtensions by lazy { RegexJsExtensions(name) }
    return runBlocking {
        suspendCancellableCoroutine { block ->
            Coroutine.async(executeContext = IO) {
                val job = launch {
                    try {
                        val pattern = regex.toPattern()
                        val matcher = pattern.matcher(charSequence)
                        val stringBuffer = StringBuffer()
                        while (matcher.find()) {
                            coroutineContext.ensureActive()
                            if (isJs) {
                                val jsResult =
                                    V8ScriptExecutor.evaluateReplacement(
                                        replacement1,
                                        mapOf(
                                            "result" to matcher.group(),
                                            "chapter" to chapter?.let(DartSourceEngine::jsonObject),
                                            "book" to book?.let(DartSourceEngine::jsonObject),
                                        ),
                                        reJsExtensions,
                                        timeoutMillis = timeout,
                                    )
                                val quotedResult = jsResult.quoteReplacementJs()
                                matcher.appendReplacement(stringBuffer, quotedResult)
                            } else {
                                matcher.appendReplacement(stringBuffer, replacement1)
                            }
                        }
                        matcher.appendTail(stringBuffer)
                        if (block.isActive) block.resume(stringBuffer.toString())
                    } catch (e: Exception) {
                        if (block.isActive) block.resumeWithException(e)
                    }
                }
                block.invokeOnCancellation { job.cancel() }
                select {
                    job.onJoin {}
                    onTimeout(timeout) {
                        val content =
                            if (includeContentInTimeoutMessage) {
                                "\n替换内容:$charSequence"
                            } else {
                                ""
                            }
                        val timeoutMsg = "替换超时,3秒后还未结束将重启应用\n规则名称:$name\n替换规则:$regex$content"
                        val exception = RegexTimeoutException(timeoutMsg)
                        block.cancel(exception)
                        appCtx.longToastOnUi(timeoutMsg)
                        CrashHandler.saveCrashInfo2File(exception)
                        select {
                            job.onJoin {}
                            onTimeout(3000) {
                                appCtx.restart()
                            }
                        }
                    }
                }
            }
        }
    }
}
