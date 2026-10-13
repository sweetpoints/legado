package io.legado.app.model.sourceEngine

import java.util.ArrayDeque
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Task-private suspended native stacks. Script results resume the same stack exactly once. */
internal class NativeScriptContinuationHost(private val taskContext: CoroutineContext) {
    private val lock = Any()
    private val sessions = hashMapOf<String, Session>()
    private val finished = linkedSetOf<String>()
    private val scope =
        CoroutineScope(taskContext + SupervisorJob(taskContext[Job]) + Dispatchers.IO)
    @Volatile private var closed = false
    private val completion: DisposableHandle? = taskContext[Job]?.invokeOnCompletion { close() }

    suspend fun begin(request: (CoroutineContext) -> Any?): Map<String, Any?> {
        taskContext.ensureActive()
        val session =
            synchronized(lock) {
                ensureOpen()
                if (sessions.size >= 64) invalid("Too many active HTTP continuations")
                Session(UUID.randomUUID().toString()).also { sessions[it.token] = it }
            }
        val worker = scope.launch {
            val workerContext = currentCoroutineContext()
            try {
                val context =
                    workerContext +
                        LegacyUrlScriptContext { script, bindings, callbacks, executionContext ->
                            session.evaluate(script, bindings, callbacks, executionContext)
                        }
                session.finish(Result.success(request(context)))
            } catch (error: Throwable) {
                session.finish(Result.failure(error))
            }
        }
        session.attachWorker(worker)
        return deliver(session)
    }

    suspend fun resume(token: String, sequence: Int, outcome: Map<*, *>): Map<String, Any?> {
        val session = find(token)
        val ok = outcome["ok"] as? Boolean ?: invalid("Script outcome requires ok")
        val result =
            if (ok) {
                if (!outcome.containsKey("value")) invalid("Script outcome requires value")
                Result.success(outcome["value"])
            } else {
                val code = outcome["code"] as? String ?: "script_error"
                if (!Regex("[A-Za-z0-9_.-]{1,80}").matches(code))
                    invalid("Invalid script failure code")
                Result.failure(
                    SourceScriptException(
                        code,
                        (outcome["message"] as? String).orEmpty().take(4096),
                    )
                )
            }
        try {
            session.resume(sequence, result)
        } catch (error: Throwable) {
            if (session.isTerminal()) release(session)
            throw error
        }
        return deliver(session)
    }

    suspend fun stepCall(token: String, sequence: Int, operation: String, args: List<Any?>): Any? {
        val session = find(token)
        try {
            return session.stepCall(sequence, operation, args)
        } catch (error: Throwable) {
            if (session.isTerminal()) release(session)
            throw error
        }
    }

    fun abort(token: String) {
        val session =
            synchronized(lock) {
                ensureOpen()
                val current = sessions.remove(token)
                if (current == null && token !in finished) invalid("Unknown HTTP continuation")
                remember(token)
                current
            }
        session?.close()
    }

    fun close() {
        val active =
            synchronized(lock) {
                if (closed) return
                closed = true
                sessions.values.toList().also {
                    sessions.clear()
                    finished.clear()
                }
            }
        active.forEach { it.close() }
        scope.cancel()
        completion?.dispose()
    }

    private fun find(token: String): Session =
        synchronized(lock) {
            ensureOpen()
            sessions[token] ?: invalid("Unknown HTTP continuation")
        }

    private fun remember(token: String) {
        finished += token
        while (finished.size > 128) finished.remove(finished.first())
    }

    private fun release(session: Session) {
        synchronized(lock) {
            sessions.remove(session.token)
            remember(session.token)
        }
        session.close()
    }

    private suspend fun deliver(session: Session): Map<String, Any?> {
        var terminalDelivered = false
        try {
            val event = session.next()
            if (event is Event.Script) {
                val step = event.step
                return mapOf(
                    "status" to "script",
                    "token" to session.token,
                    "sequence" to step.sequence,
                    "script" to step.script,
                    "bindings" to step.bindings,
                )
            }
            terminalDelivered = true
            val value = (event as Event.Terminal).result.getOrThrow()
            return mapOf("status" to "done", "token" to session.token, "value" to value)
        } catch (error: CancellationException) {
            synchronized(lock) {
                sessions.remove(session.token)
                remember(session.token)
            }
            session.close()
            throw error
        } finally {
            if (terminalDelivered) release(session)
        }
    }

    private fun ensureOpen() {
        if (closed) invalid("HTTP continuation task is closed")
    }

    private sealed interface Event {
        class Script(val step: Step) : Event

        class Terminal(val result: Result<Any?>) : Event
    }

    private class Step(
        val sequence: Int,
        val script: String,
        val bindings: Map<String, Any?>,
        val callbacks: SourceHostCallbacks,
    ) {
        val reply = CompletableDeferred<Any?>()
        var calls = 0
    }

    private class Session(val token: String) {
        private val lock = Any()
        private val signal = Channel<Unit>(Channel.CONFLATED)
        private val queue = ArrayDeque<Step>()
        private val waiting = hashSetOf<Step>()
        private var active: Step? = null
        private var sequence = 0
        private var terminal: Event.Terminal? = null
        private var closed = false
        private var worker: Job? = null

        fun attachWorker(job: Job) {
            val cancel =
                synchronized(lock) {
                    worker = job
                    closed
                }
            if (cancel) job.cancel()
        }

        fun evaluate(
            script: String,
            bindings: Map<String, Any?>,
            callbacks: SourceHostCallbacks,
            worker: CoroutineContext,
        ): Any? {
            worker.ensureActive()
            val step =
                synchronized(lock) {
                    if (closed || terminal != null)
                        throw CancellationException("HTTP continuation closed")
                    Step(++sequence, script, bindings.toMap(), callbacks).also {
                        queue.addLast(it)
                        waiting += it
                    }
                }
            signal.trySend(Unit)
            try {
                // The parked native worker belongs to the task, not the completed begin RPC.
                return runBlocking(worker) { step.reply.await() }
            } finally {
                synchronized(lock) { waiting.remove(step) }
            }
        }

        suspend fun next(): Event {
            while (true) {
                currentCoroutineContext().ensureActive()
                val event =
                    synchronized(lock) {
                        terminal
                            ?: if (closed) throw CancellationException("HTTP continuation closed")
                            else if (active != null) invalid("A script step is already pending")
                            else
                                queue.pollFirst()?.let {
                                    active = it
                                    Event.Script(it)
                                }
                    }
                if (event != null) return event
                signal.receive()
            }
        }

        fun resume(sequence: Int, result: Result<Any?>) {
            val step =
                synchronized(lock) {
                    val step = pending(sequence)
                    if (step.calls != 0) invalid("Script callback is still active")
                    active = null
                    step
                }
            result.fold(step.reply::complete, step.reply::completeExceptionally)
        }

        suspend fun stepCall(sequence: Int, operation: String, args: List<Any?>): Any? {
            if (operation !in setOf("get", "put") || args.size != if (operation == "get") 1 else 2)
                invalid("Invalid URL script callback")
            val step = synchronized(lock) { pending(sequence).also { it.calls++ } }
            try {
                return step.callbacks.call("analyze.$operation", args)
            } finally {
                synchronized(lock) { step.calls-- }
            }
        }

        private fun pending(sequence: Int): Step {
            terminal?.let {
                it.result.getOrThrow()
                invalid("HTTP continuation is no longer active")
            }
            if (closed) invalid("HTTP continuation is no longer active")
            return active?.takeIf { it.sequence == sequence }
                ?: invalid("HTTP continuation sequence mismatch")
        }

        fun finish(result: Result<Any?>) {
            val waiting =
                synchronized(lock) {
                    if (closed) return
                    terminal = Event.Terminal(result)
                    waiting.toList().also { queue.clear() }
                }
            waiting.forEach {
                it.reply.cancel(CancellationException("HTTP continuation completed"))
            }
            signal.trySend(Unit)
        }

        fun isTerminal(): Boolean = synchronized(lock) { terminal != null }

        fun close() {
            val pending =
                synchronized(lock) {
                    if (closed) return
                    closed = true
                    waiting.toList().also {
                        waiting.clear()
                        queue.clear()
                        active = null
                    }
                }
            pending.forEach {
                it.reply.cancel(CancellationException("HTTP continuation cancelled"))
            }
            signal.close()
            synchronized(lock) { worker }?.cancel()
        }
    }

    companion object {
        private fun invalid(message: String): Nothing =
            throw SourceScriptException("invalid_request", message)
    }
}
