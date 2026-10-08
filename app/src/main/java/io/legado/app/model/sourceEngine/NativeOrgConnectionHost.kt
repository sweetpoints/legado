package io.legado.app.model.sourceEngine

import java.io.IOException
import java.io.UncheckedIOException
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup

/** Repository-instance leases retain actual Jsoup request/cookie state across one owner's tasks. */
internal class NativeOrgConnectionHost {
    private class ResponseResource(val response: Connection.Response) {
        var references = 0
    }

    private class Lease(val owner: String, val value: Any) {
        var resource: ResponseResource? = null
        var closed = false
        var busy = false
        val jobs = hashSetOf<Job>()
    }

    private val lock = Any()
    private val leases = hashMapOf<String, Lease>()
    private val identities = hashMapOf<String, IdentityHashMap<Any, String>>()
    private val configurations = hashMapOf<String, String>()
    private val responses = IdentityHashMap<Connection.Response, ResponseResource>()
    private var closed = false

    suspend fun call(
        owner: String,
        method: String,
        args: List<Any?>,
        context: CoroutineContext,
    ): Any? {
        context.ensureActive()
        when (method) {
            "orgJsoup.connect" -> {
                require(args.size == 1 && args[0] is String) {
                    "Jsoup.connect requires a URL string"
                }
                val created = mutableListOf<String>()
                try {
                    val marker =
                        connectionMarker(
                            register(
                                owner,
                                Jsoup.connect(args[0] as String),
                                context = context,
                                created = created,
                            )
                        )
                    context.ensureActive()
                    return marker
                } catch (error: Throwable) {
                    rollback(created)
                    throw error
                }
            }
            "orgJsoup.release" -> {
                require(args.size == 1 && args[0] is String) { "Jsoup release requires a token" }
                val removed =
                    synchronized(lock) {
                        ensureOpen()
                        leases[args[0]]?.let {
                            requireOwner(it, owner)
                            leases.remove(args[0])
                            identities[it.owner]?.remove(it.value)
                            if (identities[it.owner]?.isEmpty() == true) identities.remove(it.owner)
                            it.closed = true
                            it
                        }
                    }
                if (removed != null) dispose(listOf(removed))
                return null
            }
        }
        require(args.size == 3 && args[0] is String && args[1] is String && args[2] is List<*>) {
            "Invalid Jsoup object call"
        }
        val token = args[0] as String
        val operation = args[1] as String
        val values = args[2] as List<*>
        val lease =
            synchronized(lock) {
                ensureOpen()
                val value = leases[token] ?: invalid("Jsoup object was released")
                requireOwner(value, owner)
                if (value.closed || value.busy) invalid("Jsoup object is closed or already in use")
                value.busy = true
                context[Job]?.let { value.jobs.add(it) }
                value
            }
        val created = mutableListOf<String>()
        var completed = false
        try {
            val result =
                runInterruptible(Dispatchers.IO) {
                    when (method) {
                        "orgJsoup.connectionCall" ->
                            connection(
                                owner,
                                token,
                                lease.value as? Connection ?: invalid("Connection token required"),
                                lease,
                                context,
                                created,
                                operation,
                                values,
                            )
                        "orgJsoup.responseCall" ->
                            response(
                                token,
                                lease.value as? Connection.Response
                                    ?: invalid("Response token required"),
                                operation,
                                values,
                            )
                        else -> unsupported()
                    }
                }
            context.ensureActive()
            synchronized(lock) {
                if (lease.closed) invalid("Jsoup object was released during its operation")
            }
            completed = true
            return result
        } catch (error: HttpStatusException) {
            context.ensureActive()
            throw SourceScriptException("legacy.http_error", "HTTP ${error.statusCode}", error)
        } catch (error: UncheckedIOException) {
            context.ensureActive()
            throw SourceScriptException("network_error", "Jsoup response body failed", error)
        } catch (error: IOException) {
            context.ensureActive()
            throw SourceScriptException("network_error", "Jsoup transport failed", error)
        } finally {
            if (!completed) rollback(created)
            synchronized(lock) {
                lease.busy = false
                context[Job]?.let { lease.jobs.remove(it) }
            }
        }
    }

    suspend fun updateConfiguration(owner: String, fingerprint: String) {
        val caller = currentCoroutineContext()
        val removed =
            synchronized(lock) {
                ensureOpen()
                caller.ensureActive()
                val changed =
                    configurations.put(owner, fingerprint)?.let { it != fingerprint } ?: false
                if (changed) removeOwnerLocked(owner) else emptyList()
            }
        dispose(removed)
    }

    suspend fun releaseOwner(owner: String) {
        val removed =
            synchronized(lock) {
                configurations.remove(owner)
                removeOwnerLocked(owner)
            }
        dispose(removed)
    }

    private fun removeOwnerLocked(owner: String): List<Lease> {
        val entries = leases.filterValues { it.owner == owner }
        identities.remove(owner)
        entries.keys.forEach { leases.remove(it) }
        return entries.values.onEach { it.closed = true }.toList()
    }

    suspend fun close() {
        val removed =
            synchronized(lock) {
                if (closed) return
                closed = true
                leases.values
                    .onEach { it.closed = true }
                    .toList()
                    .also {
                        leases.clear()
                        identities.clear()
                        configurations.clear()
                    }
            }
        dispose(removed)
    }

    private suspend fun dispose(values: List<Lease>) {
        val (jobs, orphaned) =
            synchronized(lock) {
                values.flatMap { it.jobs }.distinct() to values.mapNotNull(::detachResponseLocked)
            }
        jobs.forEach { it.cancel() }
        withContext(NonCancellable + Dispatchers.IO) { orphaned.forEach(::closeResponse) }
    }

    /** Connection and its independently published Response lease each retain the real stream. */
    private fun retainResponseLocked(
        lease: Lease,
        response: Connection.Response,
    ): Connection.Response? {
        if (lease.resource?.response === response) return null
        val orphaned = detachResponseLocked(lease)
        val resource = responses.getOrPut(response) { ResponseResource(response) }
        resource.references++
        lease.resource = resource
        return orphaned
    }

    private fun detachResponseLocked(lease: Lease): Connection.Response? {
        val resource = lease.resource ?: return null
        lease.resource = null
        resource.references--
        if (resource.references != 0) return null
        responses.remove(resource.response)
        return resource.response
    }

    private fun closeResponse(response: Connection.Response) {
        runCatching { response.bodyStream().close() }
    }

    private fun trackResponse(
        lease: Lease,
        response: Connection.Response,
        context: CoroutineContext,
    ) {
        var orphaned: Connection.Response? = null
        try {
            synchronized(lock) {
                context.ensureActive()
                if (closed || lease.closed) invalid("Connection was released during its request")
                orphaned = retainResponseLocked(lease, response)
            }
        } catch (error: Throwable) {
            // A cancelled HTTP operation can finish after owner clear. It must neither publish
            // nor retain a new stream; an already held Response still belongs to its live lease.
            val unowned = synchronized(lock) { !responses.containsKey(response) }
            if (unowned) closeResponse(response)
            throw error
        }
        orphaned?.let(::closeResponse)
    }

    private fun connection(
        owner: String,
        token: String,
        value: Connection,
        lease: Lease,
        context: CoroutineContext,
        created: MutableList<String>,
        method: String,
        args: List<*>,
    ): Any? {
        fun count(n: Int) {
            require(args.size == n) { "Invalid Connection overload" }
        }
        fun text(n: Int) = args.getOrNull(n) as? String ?: invalid("Connection string required")
        fun bool(n: Int) = args.getOrNull(n) as? Boolean ?: invalid("Connection Boolean required")
        fun int(n: Int): Int {
            val number = args.getOrNull(n) as? Number ?: invalid("Connection integer required")
            val d = number.toDouble()
            if (
                !d.isFinite() || d != kotlin.math.floor(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE
            )
                invalid("Invalid Connection integer")
            return d.toInt()
        }
        when (method) {
            "url" -> {
                count(1)
                value.url(text(0))
            }
            "userAgent" -> {
                count(1)
                value.userAgent(text(0))
            }
            "timeout" -> {
                count(1)
                value.timeout(int(0))
            }
            "maxBodySize" -> {
                count(1)
                value.maxBodySize(int(0))
            }
            "referrer" -> {
                count(1)
                value.referrer(text(0))
            }
            "followRedirects" -> {
                count(1)
                value.followRedirects(bool(0))
            }
            "ignoreHttpErrors" -> {
                count(1)
                value.ignoreHttpErrors(bool(0))
            }
            "ignoreContentType" -> {
                count(1)
                value.ignoreContentType(bool(0))
            }
            "method" -> {
                count(1)
                value.method(Connection.Method.valueOf(text(0)))
            }
            "postDataCharset" -> {
                count(1)
                value.postDataCharset(text(0))
            }
            "requestBody" -> {
                count(1)
                value.requestBody(text(0))
            }
            "header" -> {
                count(2)
                value.header(text(0), text(1))
            }
            "headers" -> {
                count(1)
                value.headers(strings(args[0]))
            }
            "cookie" -> {
                count(2)
                value.cookie(text(0), text(1))
            }
            "cookies" -> {
                count(1)
                value.cookies(strings(args[0]))
            }
            "data" ->
                when {
                    args.size == 1 && args[0] is Map<*, *> -> value.data(strings(args[0]))
                    args.size >= 2 && args.size % 2 == 0 ->
                        value.data(*args.indices.map(::text).toTypedArray())
                    else -> invalid("Unsupported Connection.data overload")
                }
            "get" -> {
                count(0)
                val document = value.get()
                trackResponse(lease, value.response(), context)
                return LegacyDomHost.snapshot(document)
            }
            "post" -> {
                count(0)
                val document = value.post()
                trackResponse(lease, value.response(), context)
                return LegacyDomHost.snapshot(document)
            }
            "execute" -> {
                count(0)
                return responseMarker(owner, value.execute(), lease, context, created)
            }
            "response" -> {
                count(0)
                return responseMarker(
                    owner,
                    value.response(),
                    lease,
                    context,
                    created,
                )
            }
            else -> unsupported()
        }
        return connectionMarker(token)
    }

    private fun response(
        token: String,
        value: Connection.Response,
        method: String,
        args: List<*>,
    ): Any? {
        fun count(n: Int) {
            require(args.size == n) { "Invalid Response overload" }
        }
        fun text(n: Int) = args.getOrNull(n) as? String ?: invalid("Response string required")
        return when (method) {
            "parse" -> {
                count(0)
                LegacyDomHost.snapshot(value.parse())
            }
            "body" -> {
                count(0)
                value.body()
            }
            "bodyAsBytes" -> {
                count(0)
                value.bodyAsBytes().map { it.toInt() }
            }
            "statusCode" -> {
                count(0)
                value.statusCode()
            }
            "statusMessage" -> {
                count(0)
                value.statusMessage()
            }
            "header" -> {
                count(1)
                value.header(text(0))
            }
            "headers" -> {
                count(0)
                value.headers()
            }
            "multiHeaders" -> {
                count(0)
                value.multiHeaders()
            }
            "cookie" -> {
                count(1)
                value.cookie(text(0))
            }
            "cookies" -> {
                count(0)
                value.cookies()
            }
            "hasHeader" -> {
                count(1)
                value.hasHeader(text(0))
            }
            "hasCookie" -> {
                count(1)
                value.hasCookie(text(0))
            }
            "url" -> {
                count(0)
                value.url().toString()
            }
            "charset" ->
                if (args.isEmpty()) value.charset()
                else {
                    count(1)
                    value.charset(text(0))
                    mapOf("__legacyOrgResponse" to token)
                }
            else -> unsupported()
        }
    }

    private fun responseMarker(
        owner: String,
        value: Connection.Response,
        parent: Lease,
        context: CoroutineContext,
        created: MutableList<String>,
    ): Map<String, Any?> {
        // Execute publishes headers without consuming the real response stream. The caller
        // selects body(), bodyAsBytes() or parse(); post/get may already have parsed that stream.
        trackResponse(parent, value, context)
        val encoded = mapOf("__legacyResponseKind" to "jsoup")
        val token = register(owner, value, parent, context, created)
        return encoded + mapOf("__legacyOrgResponse" to token)
    }

    private fun register(
        owner: String,
        value: Any,
        parent: Lease? = null,
        context: CoroutineContext,
        created: MutableList<String>,
    ): String =
        synchronized(lock) {
            context.ensureActive()
            ensureOpen()
            if (parent != null && parent.closed)
                invalid("Connection was released before response registration")
            val owned = identities.getOrPut(owner) { IdentityHashMap() }
            owned[value]?.let {
                return@synchronized it
            }
            UUID.randomUUID().toString().also {
                val lease = Lease(owner, value)
                if (value is Connection.Response) retainResponseLocked(lease, value)
                leases[it] = lease
                owned[value] = it
                created.add(it)
            }
        }

    private suspend fun rollback(tokens: List<String>) {
        val removed =
            synchronized(lock) {
                tokens.mapNotNull { token ->
                    leases.remove(token)?.also {
                        it.closed = true
                        identities[it.owner]?.remove(it.value)
                        if (identities[it.owner]?.isEmpty() == true) identities.remove(it.owner)
                    }
                }
            }
        dispose(removed)
    }

    private fun requireOwner(value: Lease, owner: String) {
        if (value.owner != owner) invalid("Jsoup object belongs to another source owner")
    }

    private fun ensureOpen() {
        if (closed) invalid("Jsoup manager is closed")
    }

    private fun connectionMarker(token: String) = mapOf("__legacyOrgConnection" to token)

    companion object {
        val methods =
            setOf(
                "orgJsoup.connect",
                "orgJsoup.connectionCall",
                "orgJsoup.responseCall",
                "orgJsoup.release",
            )

        private fun strings(value: Any?): Map<String, String> {
            val map = value as? Map<*, *> ?: invalid("Connection map required")
            return map.entries.associate { (key, item) ->
                (key as? String ?: invalid("Connection map key must be a string")) to
                    (item as? String ?: invalid("Connection map value must be a string"))
            }
        }

        private fun invalid(message: String): Nothing =
            throw SourceScriptException("invalid_request", message)

        private fun unsupported(): Nothing =
            throw SourceScriptException("legacy.unsupported_org_api", "Unsupported org.jsoup API")
    }
}
