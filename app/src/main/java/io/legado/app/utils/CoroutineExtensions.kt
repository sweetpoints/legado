package io.legado.app.utils

import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TimeoutCancellationException(msg: String) : CancellationException(msg)

/** Start cleanup before the owning lifecycle ends, keeping only cleanup non-cancellable. */
fun CoroutineScope.launchCleanup(block: suspend CoroutineScope.() -> Unit): Job =
    launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable, block) }

inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

suspend fun <T> withTimeoutAsync(delayMillis: Long, block: suspend CoroutineScope.() -> T): T {
    return suspendCancellableCoroutine { cout ->
        Coroutine.async(context = cout.context) {
            launch {
                delay(delayMillis)
                if (!cout.isCompleted) {
                    cout.resumeWithException(TimeoutCancellationException("Timed out waiting for $delayMillis ms"))
                }
            }
            val result = block()
            if (!cout.isCompleted) {
                cout.resume(result)
            }
        }
    }
}

suspend fun <T> withTimeoutOrNullAsync(delayMillis: Long, block: suspend CoroutineScope.() -> T): T? {
    return try {
        withTimeoutAsync(delayMillis, block)
    } catch (e: TimeoutCancellationException) {
        null
    }
}
