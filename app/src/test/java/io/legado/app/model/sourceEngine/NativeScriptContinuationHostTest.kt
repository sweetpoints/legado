package io.legado.app.model.sourceEngine

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Protocol tests execute the real task-owned worker and queues, not a fake JS runtime. */
class NativeScriptContinuationHostTest {
    private fun token(step: Map<String, Any?>) = step["token"] as String

    private fun sequence(step: Map<String, Any?>) = (step["sequence"] as Number).toInt()

    private fun answer(value: Any?) = mapOf("ok" to true, "value" to value)

    private val callbacks = SourceHostCallbacks { method, args ->
        assertEquals("analyze.get", method)
        assertEquals(listOf("key"), args)
        "native-scope"
    }

    private suspend fun invalid(block: suspend () -> Any?) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue(error is SourceScriptException)
        assertEquals("invalid_request", (error as SourceScriptException).code)
    }

    @Test
    fun aBatchFailureAfterAPublishedStepKeepsTheOriginalErrorInsteadOfInvalidToken(): Unit =
        runBlocking(Dispatchers.IO) {
            val host = NativeScriptContinuationHost(currentCoroutineContext())
            val fail = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            try {
                val step = host.begin { context ->
                    runBlocking(context) {
                        try {
                            coroutineScope {
                                val script =
                                    async(Dispatchers.IO) {
                                        val child = currentCoroutineContext()
                                        child[LegacyUrlScriptContext]!!.evaluator(child)(
                                            "waiting",
                                            emptyMap(),
                                            callbacks,
                                        )
                                    }
                                val failure = async {
                                    fail.await()
                                    throw SourceScriptException(
                                        "network_error",
                                        "controlled batch failure",
                                    )
                                }
                                awaitAll(script, failure)
                            }
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                }
                fail.complete(Unit)
                withTimeout(2_000) { stopped.await() }
                val error = runCatching {
                    host.resume(token(step), sequence(step), answer("late"))
                }
                    .exceptionOrNull()
                assertTrue(error is SourceScriptException)
                assertEquals("network_error", (error as SourceScriptException).code)
            } finally {
                host.close()
            }
        }

    @Test
    fun twoScriptStepsKeepOneNativeStackAndRejectRepeatedOrForeignReplies(): Unit =
        runBlocking(Dispatchers.IO) {
            val host = NativeScriptContinuationHost(currentCoroutineContext())
            val foreign = NativeScriptContinuationHost(currentCoroutineContext())
            val sends = AtomicInteger()
            try {
                val first =
                    withTimeout(2_000) {
                        host.begin { context ->
                            val eval = context[LegacyUrlScriptContext]!!.evaluator(context)
                            val url =
                                eval("url-script", mapOf("result" to "original-url"), callbacks)
                            sends.incrementAndGet()
                            val body = eval("body-script", mapOf("result" to "raw-body"), callbacks)
                            listOf(url, body)
                        }
                    }
                assertEquals("script", first["status"])
                assertEquals("url-script", first["script"])
                assertEquals(0, sends.get())
                invalid { foreign.resume(token(first), sequence(first), answer("wrong-owner")) }
                invalid { host.resume(token(first), sequence(first) + 1, answer("wrong-step")) }
                assertEquals(
                    "native-scope",
                    host.stepCall(token(first), sequence(first), "get", listOf("key")),
                )
                val second =
                    withTimeout(2_000) {
                        host.resume(token(first), sequence(first), answer("resolved-url"))
                    }
                assertEquals("body-script", second["script"])
                assertEquals(mapOf("result" to "raw-body"), second["bindings"])
                assertEquals(1, sends.get())
                invalid { host.resume(token(first), sequence(first), answer("duplicate")) }
                invalid { host.stepCall(token(first), sequence(first), "get", listOf("key")) }
                val done =
                    withTimeout(2_000) {
                        host.resume(token(second), sequence(second), answer("processed-body"))
                    }
                assertEquals("done", done["status"])
                assertEquals(listOf("resolved-url", "processed-body"), done["value"])
                assertEquals(1, sends.get())
                host.abort(
                    token(done)
                ) // The caller's finally may abort an already completed session.
                invalid { host.resume(token(done), sequence(second), answer("late")) }
            } finally {
                host.close()
                foreign.close()
            }
        }

    @Test
    fun parallelBatchScriptRequestsRemainDistinctAndAllWorkersResume(): Unit =
        runBlocking(Dispatchers.IO) {
            val host = NativeScriptContinuationHost(currentCoroutineContext())
            try {
                var step =
                    withTimeout(2_000) {
                        host.begin { context ->
                            runBlocking(context) {
                                coroutineScope {
                                    (0..3)
                                        .map { index ->
                                            async(Dispatchers.IO) {
                                                val childContext = currentCoroutineContext()
                                                childContext[LegacyUrlScriptContext]!!.evaluator(
                                                    childContext
                                                )(
                                                    "batch-$index",
                                                    mapOf("index" to index),
                                                    callbacks,
                                                )
                                            }
                                        }
                                        .awaitAll()
                                }
                            }
                        }
                    }
                val seen = mutableSetOf<Int>()
                repeat(4) {
                    val index = ((step["bindings"] as Map<*, *>)["index"] as Number).toInt()
                    assertTrue(seen.add(index))
                    step =
                        withTimeout(2_000) {
                            host.resume(token(step), sequence(step), answer(index))
                        }
                }
                assertEquals(setOf(0, 1, 2, 3), seen)
                assertEquals("done", step["status"])
                assertEquals(listOf(0, 1, 2, 3), step["value"])
            } finally {
                host.close()
            }
        }

    @Test
    fun continuationCannotConsumeAReplyWhileItsNativeCallbackIsStillRunning(): Unit =
        runBlocking(Dispatchers.IO) {
            val host = NativeScriptContinuationHost(currentCoroutineContext())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val slow = SourceHostCallbacks { _, _ ->
                entered.complete(Unit)
                release.await()
                "ready"
            }
            try {
                val step = host.begin { context ->
                    context[LegacyUrlScriptContext]!!.evaluator(context)("script", emptyMap(), slow)
                }
                val callback = async {
                    host.stepCall(token(step), sequence(step), "get", listOf("key"))
                }
                withTimeout(2_000) { entered.await() }
                invalid { host.resume(token(step), sequence(step), answer("too-early")) }
                release.complete(Unit)
                assertEquals("ready", callback.await())
                assertEquals(
                    "done",
                    host.resume(token(step), sequence(step), answer("accepted"))["status"],
                )
            } finally {
                release.complete(Unit)
                host.close()
            }
        }

    @Test
    fun abortAndTaskCloseUnparkTheNativeEvaluatorAndRejectRetainedTokens(): Unit =
        runBlocking(Dispatchers.IO) {
            for (taskClose in listOf(false, true)) {
                val job = Job()
                val host = NativeScriptContinuationHost(currentCoroutineContext() + job)
                val stopped = CompletableDeferred<Unit>()
                val step = host.begin { context ->
                    try {
                        context[LegacyUrlScriptContext]!!.evaluator(context)(
                            "waiting",
                            emptyMap(),
                            callbacks,
                        )
                    } finally {
                        stopped.complete(Unit)
                    }
                }
                if (taskClose) {
                    job.cancel()
                    withTimeout(2_000) { job.join() }
                } else host.abort(token(step))
                withTimeout(2_000) { stopped.await() }
                invalid { host.stepCall(token(step), sequence(step), "get", listOf("key")) }
                host.close()
                job.cancel()
            }
        }

    @Test
    fun scriptFailureIsReturnedWithoutReexecutingTheNativeRequest(): Unit =
        runBlocking(Dispatchers.IO) {
            val host = NativeScriptContinuationHost(currentCoroutineContext())
            val sends = AtomicInteger()
            try {
                val step = host.begin { context ->
                    sends.incrementAndGet()
                    context[LegacyUrlScriptContext]!!.evaluator(context)(
                        "body-script",
                        emptyMap(),
                        callbacks,
                    )
                }
                val error = runCatching {
                    host.resume(
                        token(step),
                        sequence(step),
                        mapOf(
                            "ok" to false,
                            "code" to "script_error",
                            "message" to "controlled failure",
                        ),
                    )
                }
                    .exceptionOrNull()
                assertTrue(error is SourceScriptException)
                assertEquals("script_error", (error as SourceScriptException).code)
                assertEquals(1, sends.get())
                invalid { host.resume(token(step), sequence(step), answer("retry")) }
            } finally {
                host.close()
            }
        }
}
